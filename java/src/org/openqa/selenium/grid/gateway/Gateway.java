// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.gateway;

import static org.openqa.selenium.remote.http.Contents.asJson;
import static org.openqa.selenium.remote.http.Route.combine;
import static org.openqa.selenium.remote.http.Route.delete;
import static org.openqa.selenium.remote.http.Route.get;
import static org.openqa.selenium.remote.http.Route.matching;
import static org.openqa.selenium.remote.http.Route.post;

import java.io.Closeable;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.ErrorCodec;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.Routable;
import org.openqa.selenium.remote.tracing.HttpTracing;
import org.openqa.selenium.remote.tracing.Span;
import org.openqa.selenium.remote.tracing.Tracer;
import org.openqa.selenium.status.HasReadyState;

/**
 * Service Gateway that provides enhanced routing, load balancing, and service discovery for
 * multiple Grid instances. Extends GridLoadBalancer functionality with YAML-based routing rules and
 * additional Gateway-specific endpoints.
 *
 * <p>The Gateway provides:
 *
 * <ul>
 *   <li>YAML-based routing rules with match criteria and weight distribution
 *   <li>Enhanced discovery endpoints (POST/DELETE /discovery)
 *   <li>Individual Grid instance status endpoints
 *   <li>Smart load balancing with capability checking and routing rules
 *   <li>All existing GridLoadBalancer functionality (session affinity, health checking, etc.)
 * </ul>
 */
public class Gateway implements HasReadyState, Routable, Closeable {

  private static final Logger LOG = Logger.getLogger(Gateway.class.getName());

  private final GridLoadBalancer gridLoadBalancer;
  // RoutingRuleEngine removed - using LoadBalancingStrategy instead
  private final Routable routes;
  private final Tracer tracer;
  private final GridInstanceRegistry gridInstanceRegistry;
  private final HttpClient.Factory httpClientFactory;
  private final SessionGridMapping sessionGridMapping;
  private final Json json;
  private final AtomicInteger routingRuleRequests;
  private final AtomicInteger discoveryRequests;
  private volatile RoutingRulesConfig currentRoutingRulesConfig;

  public Gateway(
      Tracer tracer,
      HttpClient.Factory httpClientFactory,
      GridInstanceRegistry gridInstanceRegistry,
      RoutingRulesConfig routingRulesConfig,
      int maxRetryAttempts,
      Duration requestTimeout,
      URI publicUri,
      String version) {

    this.tracer = Require.nonNull("Tracer", tracer);
    this.httpClientFactory = Require.nonNull("HTTP client factory", httpClientFactory);
    this.gridInstanceRegistry = Require.nonNull("Grid instance registry", gridInstanceRegistry);
    this.json = new Json();
    this.routingRuleRequests = new AtomicInteger(0);
    this.discoveryRequests = new AtomicInteger(0);

    // Create the underlying GridLoadBalancer
    this.gridLoadBalancer =
        new GridLoadBalancer(
            tracer,
            httpClientFactory,
            gridInstanceRegistry,
            maxRetryAttempts,
            requestTimeout,
            publicUri,
            version);

    // Get the session mapping from the GridLoadBalancer
    this.sessionGridMapping = gridLoadBalancer.getSessionGridMapping();

    // Store current routing rules config
    this.currentRoutingRulesConfig = routingRulesConfig;

    // Routing rules are now integrated into the load balancing strategy
    if (routingRulesConfig != null && routingRulesConfig.hasRoutingRules()) {
      LOG.info(
          "Gateway initialized with "
              + routingRulesConfig.getRoutingRules().size()
              + " routing rules");
    } else {
      LOG.info("Gateway initialized without routing rules - using default load balancing");
    }

    // Set up Gateway-specific routes
    this.routes =
        combine(

            // Discovery endpoints (Gateway-specific)
            get("/discovery").to(() -> new DiscoveryListHandler()),
            post("/discovery").to(() -> new DiscoveryRegistrationHandler()),
            matching(
                    req -> {
                      String uri = req.getUri();
                      return uri.startsWith("/discovery/") && uri.split("/").length == 3;
                    })
                .to(() -> new DiscoveryHandler()),
            delete("/discovery").to(() -> new DiscoveryUnregistrationHandler()),

            // Routing rules endpoints
            get("/routing-rules").to(() -> new RoutingRulesGetHandler()),
            post("/routing-rules").to(() -> new RoutingRulesUpdateHandler()),
            matching(
                    req ->
                        req.getUri().equals("/routing-rules/enabled")
                            && "POST".equals(req.getMethod().toString()))
                .to(() -> new RoutingRulesToggleHandler()),

            // Individual Grid instance status (Gateway-specific)
            matching(req -> req.getUri().startsWith("/status/"))
                .to(() -> new IndividualStatusHandler()),

            // Delegate session requests to GridLoadBalancer (exclude WebSocket requests)
            matching(req -> req.getUri().startsWith("/session") && !isWebSocketRequest(req))
                .to(() -> gridLoadBalancer),

            // Gateway GraphQL endpoint with merged responses
            matching(req -> req.getUri().equals("/graphql"))
                .to(
                    () ->
                        new GatewayGraphqlHandler(
                            tracer, gridInstanceRegistry, httpClientFactory, publicUri, version)),

            // Delegate specific requests to GridLoadBalancer (avoid catch-all)
            matching(req -> req.getUri().startsWith("/se/") || req.getUri().startsWith("/status"))
                .to(() -> gridLoadBalancer));

    LOG.info("Gateway initialized successfully");
  }

  @Override
  public boolean isReady() {
    return gridLoadBalancer.isReady();
  }

  @Override
  public boolean matches(HttpRequest req) {
    return routes.matches(req);
  }

  @Override
  public HttpResponse execute(HttpRequest req) {
    return routes.execute(req);
  }

  @Override
  public void close() {
    if (gridLoadBalancer != null) {
      gridLoadBalancer.close();
    }
  }

  /** Check if the request is a WebSocket upgrade request. */
  private boolean isWebSocketRequest(HttpRequest req) {
    String connection = req.getHeader("Connection");
    String upgrade = req.getHeader("Upgrade");

    return (connection != null && connection.toLowerCase().contains("upgrade"))
        && (upgrade != null && upgrade.toLowerCase().equals("websocket"));
  }

  /**
   * Get the underlying GridLoadBalancer instance.
   *
   * @return the GridLoadBalancer instance
   */
  public GridLoadBalancer getGridLoadBalancer() {
    return gridLoadBalancer;
  }

  /**
   * Get the routing rule engine.
   *
   * @return the RoutingRuleEngine instance, or null if no rules are configured
   */
  // getRoutingRuleEngine() removed - using LoadBalancingStrategy instead

  /** Enhanced session handler that uses routing rules for Grid instance selection. */
  private class EnhancedSessionHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "gateway.enhanced_session")) {

        // Delegate to GridLoadBalancer (routing rules now integrated into load balancing strategy)
        routingRuleRequests.incrementAndGet();
        return gridLoadBalancer.execute(req);
      }
    }

    /** Creates a session directly on the specified Grid instance. */
    private HttpResponse createSessionOnGridInstance(
        HttpRequest req, GridInstance gridInstance, Span span) {
      try {
        // Create HTTP client for the selected Grid instance
        ClientConfig config =
            ClientConfig.defaultConfig()
                .baseUri(gridInstance.getBaseUri())
                .readTimeout(Duration.ofMinutes(5)) // Long timeout for session creation
                .connectionTimeout(Duration.ofSeconds(30));

        HttpClient client = Gateway.this.httpClientFactory.createClient(config);

        // Forward the session creation request to the selected Grid instance
        HttpRequest forwardedRequest = new HttpRequest(req.getMethod(), req.getUri());

        // Copy headers
        req.getHeaderNames()
            .forEach(
                name -> {
                  req.getHeaders(name).forEach(value -> forwardedRequest.addHeader(name, value));
                });

        // Copy request body
        String content = org.openqa.selenium.remote.http.Contents.string(req);
        if (content != null && !content.trim().isEmpty()) {
          forwardedRequest.setContent(org.openqa.selenium.remote.http.Contents.utf8String(content));
        }

        span.setAttribute("gateway.target_uri", gridInstance.getBaseUri().toString());
        span.setAttribute("gateway.direct_session_creation", true);

        LOG.info(
            "Creating session directly on Grid instance "
                + gridInstance.getId()
                + " at "
                + gridInstance.getBaseUri());

        // Execute the request on the selected Grid instance
        HttpResponse response = client.execute(forwardedRequest);

        // If session creation was successful, store the session mapping
        if (response.getStatus() == 200) {
          String responseContent = org.openqa.selenium.remote.http.Contents.string(response);
          Optional<SessionId> sessionId = extractSessionIdFromResponse(responseContent);

          if (sessionId.isPresent()) {
            // Store session-to-Grid-instance mapping for future requests
            Gateway.this.sessionGridMapping.mapSession(sessionId.get(), gridInstance.getId());
            LOG.info(
                "Gateway stored session mapping: "
                    + sessionId.get()
                    + " -> "
                    + gridInstance.getId());
            span.setAttribute("gateway.session_id", sessionId.get().toString());
            span.setAttribute("gateway.session_mapped", true);
          } else {
            LOG.warning("Failed to extract session ID from successful response");
            span.setAttribute("gateway.session_mapped", false);
          }
        } else {
          LOG.warning(
              "Session creation failed on Grid instance "
                  + gridInstance.getId()
                  + " with status: "
                  + response.getStatus());
          span.setAttribute("gateway.session_creation_failed", true);
          span.setAttribute("gateway.response_status", response.getStatus());
        }

        return response;

      } catch (Exception e) {
        LOG.log(
            Level.WARNING, "Failed to create session on Grid instance " + gridInstance.getId(), e);
        span.setAttribute("gateway.error", e.getMessage());

        // Return error response
        HttpResponse errorResponse = new HttpResponse();
        errorResponse.setStatus(500);
        errorResponse.setContent(
            asJson(
                ErrorCodec.createDefault()
                    .encode(
                        new SessionNotCreatedException(
                            "Failed to create session on Grid instance "
                                + gridInstance.getId()
                                + ": "
                                + e.getMessage()))));
        return errorResponse;
      }
    }

    /** Extracts session ID from the response content. */
    private Optional<SessionId> extractSessionIdFromResponse(String responseContent) {
      try {
        if (responseContent == null || responseContent.trim().isEmpty()) {
          return Optional.empty();
        }

        Map<String, Object> responseMap = json.toType(responseContent, Map.class);

        // Handle nested response format: {"value": {"sessionId": "..."}}
        Object value = responseMap.get("value");
        if (value instanceof Map) {
          Map<String, Object> valueMap = (Map<String, Object>) value;
          Object sessionId = valueMap.get("sessionId");
          if (sessionId instanceof String) {
            return Optional.of(new SessionId((String) sessionId));
          }
        }

        // Handle direct format: {"sessionId": "..."}
        Object sessionId = responseMap.get("sessionId");
        if (sessionId instanceof String) {
          return Optional.of(new SessionId((String) sessionId));
        }

        return Optional.empty();
      } catch (Exception e) {
        LOG.warning("Failed to extract session ID from response: " + e.getMessage());
        return Optional.empty();
      }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractCapabilitiesFromRequest(HttpRequest req) {
      try {
        String content = org.openqa.selenium.remote.http.Contents.string(req);
        if (content == null || content.trim().isEmpty()) {
          return null;
        }

        Map<String, Object> requestBody = json.toType(content, Map.class);
        Object capabilities = requestBody.get("capabilities");

        if (capabilities instanceof Map) {
          Map<String, Object> caps = (Map<String, Object>) capabilities;
          // Handle W3C capabilities format
          Object alwaysMatch = caps.get("alwaysMatch");
          if (alwaysMatch instanceof Map) {
            return (Map<String, Object>) alwaysMatch;
          }
          return caps;
        }

        return null;
      } catch (Exception e) {
        LOG.warning("Failed to parse capabilities from request: " + e.getMessage());
        return null;
      }
    }
  }

  /** Handler for discovery list endpoint (GET /discovery). */
  private class DiscoveryListHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "gateway.discovery_list")) {
        List<Map<String, Object>> instances = new ArrayList<>();

        for (GridInstance instance : gridInstanceRegistry.getAllGridInstances()) {
          instances.add(
              Map.of(
                  "id", instance.getId(),
                  "url", instance.getBaseUri().toString(),
                  "enabled", instance.isEnabled()));
        }

        HttpResponse response = new HttpResponse();
        response.setStatus(200);
        response.setContent(asJson(Map.of("instances", instances)));
        return response;
      }
    }
  }

  /** Handler for discovery instance-specific operations (PUT/DELETE /discovery/{instanceId}). */
  private class DiscoveryHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      String method = req.getMethod().toString();

      if ("POST".equals(method)) {
        return handleUpdate(req);
      } else if ("DELETE".equals(method)) {
        return handleDelete(req);
      } else {
        HttpResponse response = new HttpResponse();
        response.setStatus(405);
        response.setContent(asJson(Map.of("error", "Method not allowed")));
        return response;
      }
    }

    private HttpResponse handleUpdate(HttpRequest req) {
      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "gateway.discovery_update")) {
        String uri = req.getUri();
        String[] parts = uri.split("/");
        String instanceId = parts[2];

        try {
          String content = org.openqa.selenium.remote.http.Contents.string(req);
          Map<String, Object> updateData = json.toType(content, Map.class);
          Boolean enabled = (Boolean) updateData.get("enabled");

          if (enabled == null) {
            HttpResponse response = new HttpResponse();
            response.setStatus(400);
            response.setContent(asJson(Map.of("error", "'enabled' field is required")));
            return response;
          }

          Optional<GridInstance> instance =
              gridInstanceRegistry.getAllGridInstances().stream()
                  .filter(gi -> gi.getId().equals(instanceId))
                  .findFirst();

          if (instance.isEmpty()) {
            HttpResponse response = new HttpResponse();
            response.setStatus(404);
            response.setContent(asJson(Map.of("error", "Grid instance not found: " + instanceId)));
            return response;
          }

          instance.get().setEnabled(enabled);

          LOG.info("Grid instance " + instanceId + " " + (enabled ? "enabled" : "disabled"));

          HttpResponse response = new HttpResponse();
          response.setStatus(200);
          response.setContent(
              asJson(
                  Map.of(
                      "status",
                      "updated",
                      "instanceId",
                      instanceId,
                      "enabled",
                      enabled,
                      "message",
                      "Grid instance " + (enabled ? "enabled" : "disabled") + " successfully")));
          return response;

        } catch (Exception e) {
          LOG.warning("Failed to update grid instance " + instanceId + ": " + e.getMessage());
          HttpResponse response = new HttpResponse();
          response.setStatus(400);
          response.setContent(
              asJson(Map.of("error", "Failed to update grid instance", "message", e.getMessage())));
          return response;
        }
      }
    }

    private HttpResponse handleDelete(HttpRequest req) {
      // Handle DELETE for specific instance ID
      return new DiscoveryUnregistrationHandler().execute(req);
    }
  }

  /** Handler for discovery registration endpoint (POST /discovery). */
  private class DiscoveryRegistrationHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "gateway.discovery_register")) {
        discoveryRequests.incrementAndGet();

        try {
          String content = org.openqa.selenium.remote.http.Contents.string(req);
          Map<String, Object> registrationData = json.toType(content, Map.class);

          String url = (String) registrationData.get("url");
          if (url == null) {
            HttpResponse response = new HttpResponse();
            response.setStatus(400);
            response.setContent(asJson(Map.of("error", "URL is required for registration")));
            return response;
          }

          // Check for duplicate URL
          URI gridUri = URI.create(url);
          boolean urlExists =
              gridInstanceRegistry.getAllGridInstances().stream()
                  .anyMatch(instance -> instance.getBaseUri().equals(gridUri));

          if (urlExists) {
            HttpResponse response = new HttpResponse();
            response.setStatus(409);
            response.setContent(
                asJson(
                    Map.of(
                        "error",
                        "Grid instance URL must be unique. This URL is already registered.")));
            return response;
          }

          // Register the Grid instance with the registry
          try {

            // Add to the grid instance registry using the correct method signature
            String instanceId = (String) registrationData.get("id");
            if (instanceId == null || instanceId.trim().isEmpty()) {
              instanceId = "gateway-discovered-" + System.currentTimeMillis();
            }
            gridInstanceRegistry.addGridInstance(instanceId, gridUri);

            LOG.info("Successfully registered Grid instance: " + url + " with ID: " + instanceId);
            span.setAttribute("gateway.registered_instance_id", instanceId);
            span.setAttribute("gateway.registered_instance_uri", url);

            HttpResponse response = new HttpResponse();
            response.setStatus(200);
            response.setContent(
                asJson(
                    Map.of(
                        "status",
                        "registered",
                        "url",
                        url,
                        "instanceId",
                        instanceId,
                        "message",
                        "Grid instance registered successfully")));
            return response;

          } catch (Exception registrationException) {
            LOG.warning(
                "Failed to register Grid instance "
                    + url
                    + ": "
                    + registrationException.getMessage());
            span.setAttribute("gateway.registration_error", registrationException.getMessage());

            HttpResponse response = new HttpResponse();
            response.setStatus(500);
            response.setContent(
                asJson(
                    Map.of(
                        "error",
                        "Failed to register Grid instance",
                        "url",
                        url,
                        "message",
                        registrationException.getMessage())));
            return response;
          }

        } catch (Exception e) {
          LOG.warning("Failed to process discovery registration: " + e.getMessage());
          HttpResponse response = new HttpResponse();
          response.setStatus(400);
          response.setContent(asJson(Map.of("error", "Invalid registration data")));
          return response;
        }
      }
    }
  }

  /** Handler for discovery unregistration endpoint (DELETE /discovery). */
  private class DiscoveryUnregistrationHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "gateway.discovery_unregister")) {
        discoveryRequests.incrementAndGet();

        // Extract URL from query parameters or request body
        String url = req.getQueryParameter("url");
        if (url == null) {
          try {
            String content = org.openqa.selenium.remote.http.Contents.string(req);
            if (content != null && !content.trim().isEmpty()) {
              Map<String, Object> unregistrationData = json.toType(content, Map.class);
              url = (String) unregistrationData.get("url");
            }
          } catch (Exception e) {
            LOG.warning("Failed to parse unregistration data: " + e.getMessage());
          }
        }

        if (url == null) {
          HttpResponse response = new HttpResponse();
          response.setStatus(400);
          response.setContent(asJson(Map.of("error", "URL is required for unregistration")));
          return response;
        }

        // Unregister the Grid instance from the registry
        try {
          URI gridUri = URI.create(url);

          // Find the Grid instance by URI
          Optional<GridInstance> instanceToRemove =
              gridInstanceRegistry.getAllGridInstances().stream()
                  .filter(instance -> instance.getBaseUri().equals(gridUri))
                  .findFirst();

          if (instanceToRemove.isPresent()) {
            GridInstance instance = instanceToRemove.get();

            // Remove from the grid instance registry
            gridInstanceRegistry.removeGridInstance(instance.getId());

            LOG.info(
                "Successfully unregistered Grid instance: "
                    + url
                    + " with ID: "
                    + instance.getId());
            span.setAttribute("gateway.unregistered_instance_id", instance.getId());
            span.setAttribute("gateway.unregistered_instance_uri", url);

            HttpResponse response = new HttpResponse();
            response.setStatus(200);
            response.setContent(
                asJson(
                    Map.of(
                        "status",
                        "unregistered",
                        "url",
                        url,
                        "instanceId",
                        instance.getId(),
                        "message",
                        "Grid instance unregistered successfully")));
            return response;

          } else {
            LOG.warning("Grid instance not found for unregistration: " + url);
            span.setAttribute("gateway.unregistration_not_found", url);

            HttpResponse response = new HttpResponse();
            response.setStatus(404);
            response.setContent(
                asJson(
                    Map.of(
                        "error", "Grid instance not found",
                        "url", url,
                        "message", "No Grid instance found with the specified URL")));
            return response;
          }

        } catch (Exception unregistrationException) {
          LOG.warning(
              "Failed to unregister Grid instance "
                  + url
                  + ": "
                  + unregistrationException.getMessage());
          span.setAttribute("gateway.unregistration_error", unregistrationException.getMessage());

          HttpResponse response = new HttpResponse();
          response.setStatus(500);
          response.setContent(
              asJson(
                  Map.of(
                      "error",
                      "Failed to unregister Grid instance",
                      "url",
                      url,
                      "message",
                      unregistrationException.getMessage())));
          return response;
        }
      }
    }
  }

  /** Handler for routing rules get endpoint (GET /routing-rules). */
  private class RoutingRulesGetHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "gateway.routing_rules_get")) {
        String yamlContent =
            currentRoutingRulesConfig != null && currentRoutingRulesConfig.hasRoutingRules()
                ? currentRoutingRulesConfig.toYamlString()
                : "";

        HttpResponse response = new HttpResponse();
        response.setStatus(200);
        response.setHeader("Content-Type", "application/x-yaml");
        response.setContent(org.openqa.selenium.remote.http.Contents.utf8String(yamlContent));
        return response;
      }
    }
  }

  /** Handler for routing rules toggle endpoint (POST /routing-rules/enabled). */
  private class RoutingRulesToggleHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "gateway.routing_rules_toggle")) {
        try {
          String content = org.openqa.selenium.remote.http.Contents.string(req);
          Map<String, Object> updateData = json.toType(content, Map.class);
          Boolean enabled = (Boolean) updateData.get("enabled");

          if (enabled == null) {
            HttpResponse response = new HttpResponse();
            response.setStatus(400);
            response.setContent(asJson(Map.of("error", "'enabled' field is required")));
            return response;
          }

          if (currentRoutingRulesConfig == null) {
            currentRoutingRulesConfig =
                new RoutingRulesConfig(Collections.emptyList(), Collections.emptyList(), enabled);
          } else {
            // Create new config with updated enabled status
            currentRoutingRulesConfig =
                new RoutingRulesConfig(
                    currentRoutingRulesConfig.getRoutingRules(),
                    currentRoutingRulesConfig.getInstances(),
                    enabled);
          }

          LOG.info("Routing rules " + (enabled ? "enabled" : "disabled"));

          HttpResponse response = new HttpResponse();
          response.setStatus(200);
          response.setContent(
              asJson(
                  Map.of(
                      "status",
                      "updated",
                      "enabled",
                      enabled,
                      "message",
                      "Routing rules " + (enabled ? "enabled" : "disabled") + " successfully")));
          return response;

        } catch (Exception e) {
          LOG.warning("Failed to toggle routing rules: " + e.getMessage());
          HttpResponse response = new HttpResponse();
          response.setStatus(400);
          response.setContent(
              asJson(Map.of("error", "Failed to toggle routing rules", "message", e.getMessage())));
          return response;
        }
      }
    }
  }

  /** Handler for routing rules update endpoint (POST /routing-rules). */
  private class RoutingRulesUpdateHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "gateway.routing_rules_update")) {
        try {
          String yamlContent = org.openqa.selenium.remote.http.Contents.string(req);
          if (yamlContent == null) {
            yamlContent = "";
          }

          // Parse and validate YAML routing rules
          RoutingRulesConfig newConfig = RoutingRulesConfig.fromYamlString(yamlContent);

          // Store the new configuration
          currentRoutingRulesConfig = newConfig;

          LOG.info(
              "Parsed YAML content successfully. Rules count: "
                  + (newConfig.hasRoutingRules() ? newConfig.getRoutingRules().size() : 0));

          LOG.info(
              "Routing rules updated successfully with "
                  + (newConfig.hasRoutingRules() ? newConfig.getRoutingRules().size() : 0)
                  + " rules");

          HttpResponse response = new HttpResponse();
          response.setStatus(200);
          response.setContent(
              asJson(
                  Map.of(
                      "status", "success",
                      "message", "Routing rules updated successfully",
                      "rulesCount",
                          newConfig.hasRoutingRules() ? newConfig.getRoutingRules().size() : 0)));
          return response;

        } catch (Exception e) {
          LOG.warning("Failed to update routing rules: " + e.getMessage());
          HttpResponse response = new HttpResponse();
          response.setStatus(400);
          response.setContent(
              asJson(Map.of("error", "Failed to update routing rules", "message", e.getMessage())));
          return response;
        }
      }
    }
  }

  /** Handler for individual Grid instance status endpoint (/status/{instanceId}). */
  private class IndividualStatusHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "gateway.individual_status")) {
        String uri = req.getUri();
        String[] parts = uri.split("/");

        if (parts.length < 3) {
          HttpResponse response = new HttpResponse();
          response.setStatus(400);
          response.setContent(asJson(Map.of("error", "Instance ID is required")));
          return response;
        }

        String instanceId = parts[2];
        span.setAttribute("gateway.instance_id", instanceId);

        // Find the Grid instance
        Optional<GridInstance> instance =
            gridInstanceRegistry.getAllGridInstances().stream()
                .filter(gi -> gi.getId().equals(instanceId))
                .findFirst();

        if (instance.isEmpty()) {
          HttpResponse response = new HttpResponse();
          response.setStatus(404);
          response.setContent(asJson(Map.of("error", "Grid instance not found: " + instanceId)));
          return response;
        }

        GridInstance gridInstance = instance.get();

        // TODO: Implement detailed individual instance status
        Map<String, Object> status =
            Map.of(
                "id", gridInstance.getId(),
                "baseUri", gridInstance.getBaseUri().toString(),
                "status", gridInstance.getStatus().toString(),
                "sessionCount", gridInstance.getSessionCount(),
                "lastHealthCheck", gridInstance.getLastHealthCheck().toString());

        HttpResponse response = new HttpResponse();
        response.setStatus(200);
        response.setContent(asJson(status));
        return response;
      }
    }
  }
}
