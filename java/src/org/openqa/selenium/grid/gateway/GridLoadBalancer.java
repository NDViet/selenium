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

import static org.openqa.selenium.remote.HttpSessionId.getSessionId;
import static org.openqa.selenium.remote.http.Contents.asJson;
import static org.openqa.selenium.remote.http.Route.combine;
import static org.openqa.selenium.remote.http.Route.get;
import static org.openqa.selenium.remote.http.Route.matching;
import static org.openqa.selenium.remote.http.Route.post;

import java.io.Closeable;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.grid.web.ReverseProxyHandler;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.ErrorCodec;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.Routable;
import org.openqa.selenium.remote.tracing.AttributeKey;
import org.openqa.selenium.remote.tracing.AttributeMap;
import org.openqa.selenium.remote.tracing.HttpTracing;
import org.openqa.selenium.remote.tracing.Span;
import org.openqa.selenium.remote.tracing.Status;
import org.openqa.selenium.remote.tracing.Tracer;
import org.openqa.selenium.status.HasReadyState;

/**
 * Grid Load Balancer that distributes WebDriver sessions across multiple Grid instances.
 *
 * <p>The GridLoadBalancer extends Router functionality to support multiple backend Grid instances.
 * It provides:
 *
 * <ul>
 *   <li>Session affinity - routes existing sessions to the correct Grid instance
 *   <li>Load balancing - distributes new sessions across available Grid instances
 *   <li>Failover - retries on different Grid instances if one fails
 *   <li>Health monitoring - tracks Grid instance health and availability
 * </ul>
 *
 * <p>This class responds to the same URLs as Router but distributes requests across multiple Grid
 * instances instead of routing to a single Grid deployment.
 */
public class GridLoadBalancer implements HasReadyState, Routable, Closeable {

  private static final Logger LOG = Logger.getLogger(GridLoadBalancer.class.getName());

  private final Tracer tracer;
  private final HttpClient.Factory httpClientFactory;
  private final GridInstanceRegistry gridInstanceRegistry;
  private final SessionGridMapping sessionGridMapping;
  private final Routable routes;
  private final int maxRetryAttempts;
  private final Duration requestTimeout;
  private final Json json;
  private final AtomicInteger totalRequests;
  private final AtomicInteger successfulRequests;
  private final AtomicInteger failedRequests;

  public GridLoadBalancer(
      Tracer tracer,
      HttpClient.Factory httpClientFactory,
      GridInstanceRegistry gridInstanceRegistry,
      int maxRetryAttempts,
      Duration requestTimeout,
      URI publicUri,
      String version) {
    this.tracer = Require.nonNull("Tracer", tracer);
    this.httpClientFactory = Require.nonNull("HTTP client factory", httpClientFactory);
    this.gridInstanceRegistry = Require.nonNull("Grid instance registry", gridInstanceRegistry);
    this.sessionGridMapping = SessionGridMapping.getInstance(gridInstanceRegistry);
    this.maxRetryAttempts = Math.max(1, maxRetryAttempts);
    this.requestTimeout = Require.nonNull("Request timeout", requestTimeout);
    this.json = new Json();
    this.totalRequests = new AtomicInteger(0);
    this.successfulRequests = new AtomicInteger(0);
    this.failedRequests = new AtomicInteger(0);

    // Add registration handler
    RegistrationHandler registrationHandler = new RegistrationHandler(gridInstanceRegistry);

    // Gateway integration will be handled by the Gateway wrapper class

    this.routes =
        combine(
            get("/status").to(() -> new GridLoadBalancerStatusHandler()),
            post("/session").to(() -> new NewSessionHandler()), // Handle new session creation
            matching(req -> req.getUri().startsWith("/session/") && !isWebSocketRequest(req))
                .to(() -> new ExistingSessionHandler()),
            matching(req -> req.getUri().startsWith("/se/grid/")).to(() -> new GridApiHandler()),
            // Registration endpoints
            post("/register").to(() -> registrationHandler),
            matching(req -> req.getUri().startsWith("/unregister/")).to(() -> registrationHandler),
            // GraphQL endpoints removed
            matching(req -> false)
                .to(
                    () ->
                        new HttpHandler() {
                          @Override
                          public HttpResponse execute(HttpRequest req) {
                            return new HttpResponse().setStatus(404);
                          }
                        }));
  }

  @Override
  public boolean isReady() {
    // Load balancer is ready if at least one Grid instance is available
    return !gridInstanceRegistry.getAvailableGridInstances().isEmpty();
  }

  /**
   * Check if the request is a WebSocket upgrade request. WebSocket requests should be handled by
   * the WebSocket handler, not HTTP routes.
   */
  private boolean isWebSocketRequest(HttpRequest req) {
    // Check for WebSocket upgrade headers
    String connection = req.getHeader("Connection");
    String upgrade = req.getHeader("Upgrade");

    return (connection != null && connection.toLowerCase().contains("upgrade"))
        && (upgrade != null && upgrade.toLowerCase().equals("websocket"));
  }

  @Override
  public boolean matches(HttpRequest req) {
    return routes.matches(req);
  }

  @Override
  public HttpResponse execute(HttpRequest req) {
    totalRequests.incrementAndGet();
    try {
      HttpResponse response = routes.execute(req);
      successfulRequests.incrementAndGet();
      return response;
    } catch (Exception e) {
      failedRequests.incrementAndGet();
      throw e;
    }
  }

  /**
   * Get the session-to-Grid mapping for WebSocket routing.
   *
   * @return SessionGridMapping instance
   */
  public SessionGridMapping getSessionGridMapping() {
    return sessionGridMapping;
  }

  /**
   * Get the HTTP client factory for WebSocket connections.
   *
   * @return HttpClient.Factory instance
   */
  public HttpClient.Factory getHttpClientFactory() {
    return httpClientFactory;
  }

  /**
   * Get the Grid instance registry for WebSocket routing.
   *
   * @return GridInstanceRegistry instance
   */
  public GridInstanceRegistry getGridInstanceRegistry() {
    return gridInstanceRegistry;
  }

  /**
   * Get the load balancing strategy from the registry.
   *
   * @return LoadBalancingStrategy instance
   */
  public LoadBalancingStrategy getLoadBalancingStrategy() {
    return gridInstanceRegistry.getLoadBalancingStrategy();
  }

  @Override
  public void close() {
    gridInstanceRegistry.close();
  }

  /**
   * Normalizes Content-Type header to ensure backend Grid instance compatibility. Converts
   * "application/json" to "application/json; charset=utf-8" if needed.
   */
  private HttpRequest normalizeContentTypeHeader(HttpRequest originalRequest) {
    String contentType = originalRequest.getHeader("Content-Type");

    // If Content-Type is exactly "application/json", normalize it
    if ("application/json".equals(contentType)) {
      LOG.info(
          "Normalizing Content-Type header from 'application/json' to 'application/json;"
              + " charset=utf-8'");

      // Create a new request with normalized headers
      HttpRequest normalizedRequest =
          new HttpRequest(originalRequest.getMethod(), originalRequest.getUri());

      // Copy all headers except Content-Type
      originalRequest
          .getHeaderNames()
          .forEach(
              name -> {
                if (!"Content-Type".equalsIgnoreCase(name)) {
                  originalRequest
                      .getHeaders(name)
                      .forEach(value -> normalizedRequest.addHeader(name, value));
                }
              });

      // Add the normalized Content-Type header
      normalizedRequest.addHeader("Content-Type", "application/json; charset=utf-8");

      // Copy the request body
      normalizedRequest.setContent(originalRequest.getContent());

      return normalizedRequest;
    }

    // For all other cases, return the original request unchanged
    return originalRequest;
  }

  // Gateway integration handled by Gateway wrapper class

  /** Handles status requests for the load balancer itself. */
  private class GridLoadBalancerStatusHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "loadbalancer.status")) {
        GridLoadBalancerStatus status =
            new GridLoadBalancerStatus(
                gridInstanceRegistry.getAllGridInstances(),
                sessionGridMapping.getActiveMappingCount(),
                totalRequests.get(),
                successfulRequests.get(),
                failedRequests.get());

        HttpResponse response = new HttpResponse();
        response.setContent(asJson(status));
        return response;
      }
    }
  }

  /** Handles new session creation requests with load balancing and failover. */
  private class NewSessionHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      // Log incoming request details
      LOG.info(String.format("=== INCOMING NEW SESSION REQUEST ==="));
      LOG.info(String.format("Method: %s", req.getMethod()));
      LOG.info(String.format("URI: %s", req.getUri()));
      LOG.info(String.format("Headers: %s", req.getHeaderNames()));

      // Log request body if present
      try {
        String requestBody = "";
        if (req.getHeader("Content-Length") != null
            && !req.getHeader("Content-Length").equals("0")) {
          try (java.io.Reader reader = org.openqa.selenium.remote.http.Contents.reader(req)) {
            requestBody =
                new java.io.BufferedReader(reader)
                    .lines()
                    .collect(java.util.stream.Collectors.joining("\n"));
          }
          LOG.info(String.format("Request Body: %s", requestBody));
        }
      } catch (Exception e) {
        LOG.warning("Failed to read request body: " + e.getMessage());
      }

      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "loadbalancer.new_session")) {
        AttributeMap attributeMap = tracer.createAttributeMap();

        SessionNotCreatedException lastException = null;
        GridInstance lastFailedInstance = null;

        // Check if we have retry-aware load balancer
        RetryAwareLoadBalancer retryAware = getRetryAwareLoadBalancer();

        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
          // Set retry context if available
          if (retryAware != null) {
            retryAware.setRetryCount(attempt - 1);
            if (lastFailedInstance != null) {
              retryAware.setPreviousFailedInstance(lastFailedInstance);
            }
          }

          Optional<GridInstance> selectedInstance =
              gridInstanceRegistry.selectGridInstanceForNewSession();

          if (selectedInstance.isEmpty()) {
            // Instead of hard failure, try to select from all healthy instances
            // This allows Grid instances to handle queuing even when at capacity
            selectedInstance = gridInstanceRegistry.selectFromHealthyInstances();

            if (selectedInstance.isEmpty()) {
              String message = "No healthy Grid instances available for new session";
              LOG.warning(message);
              attributeMap.put(AttributeKey.EXCEPTION_MESSAGE.getKey(), message);
              span.addEvent(AttributeKey.EXCEPTION_EVENT.getKey(), attributeMap);

              HttpResponse response = new HttpResponse();
              response.setStatus(503); // Service Unavailable
              response.setContent(
                  asJson(
                      ErrorCodec.createDefault().encode(new SessionNotCreatedException(message))));
              return response;
            } else {
              LOG.info(
                  String.format(
                      "No available Grid instances, but forwarding request to healthy instance %s"
                          + " for queuing",
                      selectedInstance.get().getId()));
            }
          }

          GridInstance instance = selectedInstance.get();
          span.setAttribute("grid.instance.id", instance.getId());
          span.setAttribute("grid.attempt", attempt);

          // Log attempt details for debugging
          LOG.info(
              String.format(
                  "Attempt %d: Trying to create session on Grid instance %s (%s) with timeout %s",
                  attempt, instance.getId(), instance.getBaseUri(), requestTimeout));

          try {
            HttpResponse response = proxyNewSessionRequest(req, instance, span);

            // If session creation was successful, extract session ID and map it
            if (response.getStatus() == 200) {
              try {
                String responseContent;
                try (java.io.Reader reader =
                    org.openqa.selenium.remote.http.Contents.reader(response)) {
                  responseContent =
                      new java.io.BufferedReader(reader)
                          .lines()
                          .collect(java.util.stream.Collectors.joining("\n"));
                } catch (Exception e) {
                  LOG.warning("Failed to read response content: " + e.getMessage());
                  responseContent = "{}";
                }
                // Parse response to extract session ID
                LOG.info(
                    String.format(
                        "Attempting to extract session ID from response: %s",
                        responseContent.length() > 200
                            ? responseContent.substring(0, 200) + "..."
                            : responseContent));

                SessionId sessionId = extractSessionIdFromResponse(responseContent);
                if (sessionId != null) {
                  sessionGridMapping.mapSession(sessionId, instance.getId());
                  span.setAttribute("session.id", sessionId.toString());
                  LOG.info(
                      String.format(
                          "SUCCESS: Created session %s on Grid instance %s and stored mapping",
                          sessionId, instance.getId()));
                } else {
                  LOG.warning(
                      "FAILED: Could not extract session ID from response - session mapping not"
                          + " stored!");
                }
              } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to extract session ID from response", e);
              }
            }

            return response;

          } catch (Exception e) {
            // Check if this is a timeout exception
            boolean isTimeout =
                e instanceof org.openqa.selenium.TimeoutException
                    || e.getCause() instanceof java.net.http.HttpTimeoutException
                    || e.getMessage().contains("timed out");

            String errorType = isTimeout ? "TIMEOUT" : "ERROR";
            lastException =
                new SessionNotCreatedException(
                    String.format(
                        "Failed to create session on Grid instance %s (%s): %s",
                        instance.getId(), errorType, e.getMessage()),
                    e);

            gridInstanceRegistry.onGridInstanceFailure(instance.getId());
            lastFailedInstance = instance;

            LOG.log(
                Level.WARNING,
                String.format(
                    "Attempt %d failed on Grid instance %s (%s) - %s",
                    attempt, instance.getId(), errorType, e.getMessage()),
                e);

            // For timeout errors, log additional diagnostic information
            if (isTimeout) {
              LOG.warning(
                  String.format(
                      "Grid instance %s timed out after %s. Consider increasing request-timeout or"
                          + " checking Grid instance health at %s",
                      instance.getId(), requestTimeout, instance.getBaseUri()));
            }

            if (attempt < maxRetryAttempts) {
              // Continue to next attempt
              continue;
            }
          } finally {
            // Clear retry context
            if (retryAware != null) {
              retryAware.clearRetryContext();
            }
          }
        }

        // All attempts failed
        String message =
            String.format("Failed to create session after %d attempts", maxRetryAttempts);
        if (lastException != null) {
          message += ": " + lastException.getMessage();
        }

        attributeMap.put(AttributeKey.EXCEPTION_MESSAGE.getKey(), message);
        span.addEvent(AttributeKey.EXCEPTION_EVENT.getKey(), attributeMap);
        span.setStatus(Status.CANCELLED);

        HttpResponse response = new HttpResponse();
        response.setStatus(500);
        response.setContent(
            asJson(
                ErrorCodec.createDefault()
                    .encode(new SessionNotCreatedException(message, lastException))));
        return response;
      }
    }

    private HttpResponse proxyNewSessionRequest(HttpRequest req, GridInstance instance, Span span) {
      ClientConfig config =
          ClientConfig.defaultConfig()
              .baseUri(instance.getBaseUri())
              .readTimeout(requestTimeout)
              .connectionTimeout(Duration.ofSeconds(30));

      LOG.info(String.format("=== FORWARDING REQUEST TO GRID INSTANCE ==="));
      LOG.info(
          String.format("Target Grid Instance: %s (%s)", instance.getId(), instance.getBaseUri()));
      LOG.info(String.format("Request Method: %s", req.getMethod()));
      LOG.info(String.format("Request URI: %s", req.getUri()));
      LOG.info(String.format("Full Target URL: %s%s", instance.getBaseUri(), req.getUri()));
      LOG.info(String.format("Timeout Configuration: request=%s, connection=30s", requestTimeout));

      // Log request headers
      for (String headerName : req.getHeaderNames()) {
        LOG.info(String.format("Request Header: %s = %s", headerName, req.getHeader(headerName)));
      }

      try (HttpClient client = httpClientFactory.createClient(config)) {
        try (ReverseProxyHandler proxy = new ReverseProxyHandler(tracer, client)) {
          LOG.info(String.format("Executing request to Grid instance %s...", instance.getId()));

          // Normalize Content-Type header for backend Grid instance compatibility
          HttpRequest normalizedRequest = normalizeContentTypeHeader(req);

          HttpResponse response = proxy.execute(normalizedRequest);

          LOG.info(String.format("=== RESPONSE FROM GRID INSTANCE %s ===", instance.getId()));
          LOG.info(String.format("Response Status: %d", response.getStatus()));

          // Log response headers
          for (String headerName : response.getHeaderNames()) {
            LOG.info(
                String.format(
                    "Response Header: %s = %s", headerName, response.getHeader(headerName)));
          }

          // Log response body for debugging (first 500 chars)
          try {
            String responseBody = "";
            try (java.io.Reader reader =
                org.openqa.selenium.remote.http.Contents.reader(response)) {
              responseBody =
                  new java.io.BufferedReader(reader)
                      .lines()
                      .collect(java.util.stream.Collectors.joining("\n"));
            }
            String truncatedBody =
                responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody;
            LOG.info(String.format("Response Body: %s", truncatedBody));
          } catch (Exception e) {
            LOG.warning("Failed to read response body: " + e.getMessage());
          }

          LOG.info(
              String.format(
                  "Successfully received response from Grid instance %s", instance.getId()));
          return response;
        }
      } catch (Exception e) {
        LOG.severe(String.format("=== ERROR FORWARDING TO GRID INSTANCE %s ===", instance.getId()));
        LOG.severe(String.format("Error Type: %s", e.getClass().getSimpleName()));
        LOG.severe(String.format("Error Message: %s", e.getMessage()));
        LOG.severe(String.format("Target URL: %s%s", instance.getBaseUri(), req.getUri()));
        if (e.getCause() != null) {
          LOG.severe(
              String.format(
                  "Root Cause: %s - %s",
                  e.getCause().getClass().getSimpleName(), e.getCause().getMessage()));
        }
        throw e;
      }
    }

    private SessionId extractSessionIdFromResponse(String responseContent) {
      try {
        LOG.info(
            String.format(
                "Extracting session ID from response content: %s",
                responseContent.length() > 300
                    ? responseContent.substring(0, 300) + "..."
                    : responseContent));

        // Parse as standard WebDriver session creation response
        // The response format is: {"value": {"sessionId": "...", "capabilities": {...}}}
        try {
          @SuppressWarnings("unchecked")
          java.util.Map<String, Object> responseMap =
              json.toType(responseContent, java.util.Map.class);

          // Handle WebDriver W3C response format: {"value": {"sessionId": "..."}}
          if (responseMap.containsKey("value")) {
            Object valueObj = responseMap.get("value");
            if (valueObj instanceof java.util.Map) {
              @SuppressWarnings("unchecked")
              java.util.Map<String, Object> valueMap = (java.util.Map<String, Object>) valueObj;
              if (valueMap.containsKey("sessionId")) {
                String sessionIdStr = String.valueOf(valueMap.get("sessionId"));
                SessionId sessionId = new SessionId(sessionIdStr);
                LOG.info(
                    String.format(
                        "Successfully extracted session ID from WebDriver response: %s",
                        sessionId));
                return sessionId;
              }
            }
          }

          // Handle direct format: {"sessionId": "..."}
          if (responseMap.containsKey("sessionId")) {
            String sessionIdStr = String.valueOf(responseMap.get("sessionId"));
            SessionId sessionId = new SessionId(sessionIdStr);
            LOG.info(
                String.format(
                    "Successfully extracted session ID from direct format: %s", sessionId));
            return sessionId;
          }

          LOG.warning("No sessionId found in WebDriver response structure");
        } catch (Exception webDriverParsingException) {
          LOG.log(
              Level.WARNING, "Failed to parse as WebDriver response", webDriverParsingException);
        }

        LOG.warning("No session ID found in response content after trying all parsing methods");
        LOG.warning(String.format("Response content for debugging: %s", responseContent));
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Failed to extract session ID from response", e);
      }
      return null;
    }

    private RetryAwareLoadBalancer getRetryAwareLoadBalancer() {
      LoadBalancingStrategy strategy = gridInstanceRegistry.getLoadBalancingStrategy();
      if (strategy instanceof RetryAwareLoadBalancer) {
        return (RetryAwareLoadBalancer) strategy;
      } else if (strategy instanceof CompositeLoadBalancer) {
        CompositeLoadBalancer composite = (CompositeLoadBalancer) strategy;
        if (composite.getBaseStrategy() instanceof RetryAwareLoadBalancer) {
          return (RetryAwareLoadBalancer) composite.getBaseStrategy();
        }
      }
      return null;
    }
  }

  /** Handles requests for existing sessions with session affinity. */
  private class ExistingSessionHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      // Log incoming existing session request
      LOG.info(String.format("=== INCOMING EXISTING SESSION REQUEST ==="));
      LOG.info(String.format("Method: %s", req.getMethod()));
      LOG.info(String.format("URI: %s", req.getUri()));
      LOG.info(String.format("Headers: %s", req.getHeaderNames()));

      // Double-check: This handler should never receive WebSocket requests due to routing logic
      if (isWebSocketRequest(req)) {
        LOG.warning(
            "🚨 UNEXPECTED: WebSocket request reached ExistingSessionHandler despite routing"
                + " exclusion!");
        LOG.warning("This indicates a routing configuration issue that needs investigation.");
        return new HttpResponse()
            .setStatus(400)
            .setContent(Contents.utf8String("WebSocket requests should not reach HTTP handler"));
      }

      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "loadbalancer.existing_session")) {
        AttributeMap attributeMap = tracer.createAttributeMap();

        // Extract session ID from URL
        Optional<String> sessionIdStr = getSessionId(req.getUri());
        LOG.info(String.format("Extracted Session ID: %s", sessionIdStr.orElse("NONE")));
        if (sessionIdStr.isEmpty()) {
          NoSuchSessionException exception =
              new NoSuchSessionException("Cannot find session: " + req);
          attributeMap.put(AttributeKey.EXCEPTION_MESSAGE.getKey(), exception.getMessage());
          span.addEvent(AttributeKey.EXCEPTION_EVENT.getKey(), attributeMap);

          HttpResponse response = new HttpResponse();
          response.setStatus(404);
          response.setContent(asJson(ErrorCodec.createDefault().encode(exception)));
          return response;
        }

        SessionId sessionId = new SessionId(sessionIdStr.get());
        span.setAttribute("session.id", sessionId.toString());

        // Find Grid instance for this session
        Optional<String> gridInstanceId = sessionGridMapping.getGridInstanceId(sessionId);
        LOG.info(
            String.format(
                "Session mapping lookup: Session %s -> Grid Instance %s",
                sessionId, gridInstanceId.orElse("NOT_FOUND")));

        if (gridInstanceId.isEmpty()) {
          LOG.warning(String.format("Session %s not found in session mapping", sessionId));
          NoSuchSessionException exception =
              new NoSuchSessionException(
                  "Session " + sessionId + " not found in any Grid instance");
          attributeMap.put(AttributeKey.EXCEPTION_MESSAGE.getKey(), exception.getMessage());
          span.addEvent(AttributeKey.EXCEPTION_EVENT.getKey(), attributeMap);

          HttpResponse response = new HttpResponse();
          response.setStatus(404);
          response.setContent(asJson(ErrorCodec.createDefault().encode(exception)));
          return response;
        }

        Optional<GridInstance> instance =
            gridInstanceRegistry.getGridInstance(gridInstanceId.get());
        LOG.info(
            String.format(
                "Grid instance lookup: %s -> %s",
                gridInstanceId.get(),
                instance.isPresent() ? instance.get().getBaseUri() : "NOT_FOUND"));

        if (instance.isEmpty()) {
          LOG.warning(
              String.format(
                  "Grid instance %s not found in registry for session %s",
                  gridInstanceId.get(), sessionId));
          NoSuchSessionException exception =
              new NoSuchSessionException(
                  "Grid instance " + gridInstanceId.get() + " not found for session " + sessionId);
          attributeMap.put(AttributeKey.EXCEPTION_MESSAGE.getKey(), exception.getMessage());
          span.addEvent(AttributeKey.EXCEPTION_EVENT.getKey(), attributeMap);

          HttpResponse response = new HttpResponse();
          response.setStatus(404);
          response.setContent(asJson(ErrorCodec.createDefault().encode(exception)));
          return response;
        }

        GridInstance gridInstance = instance.get();
        span.setAttribute("grid.instance.id", gridInstance.getId());

        try {
          HttpResponse response = proxyExistingSessionRequest(req, gridInstance);

          // If this was a DELETE request and successful, remove session mapping
          if (req.getMethod() == org.openqa.selenium.remote.http.HttpMethod.DELETE
              && response.getStatus() == 200) {
            sessionGridMapping.removeSession(sessionId);
            LOG.info(
                String.format(
                    "Session %s deleted from Grid instance %s", sessionId, gridInstance.getId()));
          }

          return response;

        } catch (Exception e) {
          gridInstanceRegistry.onGridInstanceFailure(gridInstance.getId());

          String message =
              String.format(
                  "Failed to proxy request for session %s to Grid instance %s: %s",
                  sessionId, gridInstance.getId(), e.getMessage());

          attributeMap.put(AttributeKey.EXCEPTION_MESSAGE.getKey(), message);
          span.addEvent(AttributeKey.EXCEPTION_EVENT.getKey(), attributeMap);
          span.setStatus(Status.CANCELLED);

          HttpResponse response = new HttpResponse();
          response.setStatus(500);
          response.setContent(
              asJson(ErrorCodec.createDefault().encode(new RuntimeException(message, e))));
          return response;
        }
      }
    }

    private HttpResponse proxyExistingSessionRequest(HttpRequest req, GridInstance instance) {
      ClientConfig config =
          ClientConfig.defaultConfig().baseUri(instance.getBaseUri()).readTimeout(requestTimeout);

      try (HttpClient client = httpClientFactory.createClient(config)) {
        try (ReverseProxyHandler proxy = new ReverseProxyHandler(tracer, client)) {
          return proxy.execute(req);
        }
      }
    }
  }

  /** Handles Grid API requests (status, etc.) by proxying to a healthy Grid instance. */
  private class GridApiHandler implements HttpHandler {
    @Override
    public HttpResponse execute(HttpRequest req) {
      try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "loadbalancer.grid_api")) {
        // For Grid API requests, proxy to any available Grid instance
        Optional<GridInstance> instance = gridInstanceRegistry.selectGridInstanceForNewSession();

        if (instance.isEmpty()) {
          HttpResponse response = new HttpResponse();
          response.setStatus(503); // Service Unavailable
          response.setContent(asJson("No available Grid instances"));
          return response;
        }

        GridInstance gridInstance = instance.get();
        span.setAttribute("grid.instance.id", gridInstance.getId());

        ClientConfig config =
            ClientConfig.defaultConfig()
                .baseUri(gridInstance.getBaseUri())
                .readTimeout(requestTimeout)
                .connectionTimeout(Duration.ofSeconds(30)); // Add connection timeout

        LOG.info(
            String.format(
                "Creating new session on Grid instance %s with timeout %s",
                gridInstance.getId(), requestTimeout));

        try (HttpClient client = httpClientFactory.createClient(config)) {
          try (ReverseProxyHandler proxy = new ReverseProxyHandler(tracer, client)) {
            HttpResponse response = proxy.execute(req);
            LOG.info(
                String.format(
                    "Successfully created session on Grid instance %s", gridInstance.getId()));
            return response;
          }
        } catch (Exception e) {
          LOG.warning(
              String.format(
                  "Failed to create session on Grid instance %s: %s",
                  gridInstance.getId(), e.getMessage()));
          throw e;
        }
      }
    }
  }

  /** Status information for the Grid Load Balancer. */
  public static class GridLoadBalancerStatus {
    private final java.util.Collection<GridInstance> gridInstances;
    private final int activeSessions;
    private final int totalRequests;
    private final int successfulRequests;
    private final int failedRequests;

    public GridLoadBalancerStatus(
        java.util.Collection<GridInstance> gridInstances,
        int activeSessions,
        int totalRequests,
        int successfulRequests,
        int failedRequests) {
      this.gridInstances = gridInstances;
      this.activeSessions = activeSessions;
      this.totalRequests = totalRequests;
      this.successfulRequests = successfulRequests;
      this.failedRequests = failedRequests;
    }

    public java.util.Collection<GridInstance> getGridInstances() {
      return gridInstances;
    }

    public int getActiveSessions() {
      return activeSessions;
    }

    public int getTotalRequests() {
      return totalRequests;
    }

    public int getSuccessfulRequests() {
      return successfulRequests;
    }

    public int getFailedRequests() {
      return failedRequests;
    }
  }
}
