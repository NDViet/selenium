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

import static org.openqa.selenium.json.Json.JSON_UTF_8;
import static org.openqa.selenium.remote.http.Contents.utf8String;
import static org.openqa.selenium.remote.http.HttpMethod.OPTIONS;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.tracing.HttpTracing;
import org.openqa.selenium.remote.tracing.Span;
import org.openqa.selenium.remote.tracing.Tracer;

public class GatewayGraphqlHandler implements HttpHandler {

  private static final Logger LOG = Logger.getLogger(GatewayGraphqlHandler.class.getName());
  private static final Json JSON = new Json();

  private final Tracer tracer;
  private final GridInstanceRegistry gridInstanceRegistry;
  private final HttpClient.Factory httpClientFactory;
  private final URI publicUri;
  private final String version;

  public GatewayGraphqlHandler(
      Tracer tracer,
      GridInstanceRegistry gridInstanceRegistry,
      HttpClient.Factory httpClientFactory,
      URI publicUri,
      String version) {
    this.tracer = Require.nonNull("Tracer", tracer);
    this.gridInstanceRegistry = Require.nonNull("Grid instance registry", gridInstanceRegistry);
    this.httpClientFactory = Require.nonNull("HTTP client factory", httpClientFactory);
    this.publicUri = Require.nonNull("Public URI", publicUri);
    this.version = Require.nonNull("Version", version);
  }

  @Override
  public HttpResponse execute(HttpRequest req) {
    if (req.getMethod() == OPTIONS) {
      return new HttpResponse();
    }

    try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "gateway.graphql")) {
      Map<String, Object> inputs = JSON.toType(Contents.string(req), Map.class);

      if (!(inputs.get("query") instanceof String)) {
        return new HttpResponse()
            .setStatus(500)
            .setContent(utf8String("{\"errors\":[{\"message\":\"Unable to find query\"}]}"));
      }

      String query = (String) inputs.get("query");
      Map<String, Object> variables =
          inputs.get("variables") instanceof Map
              ? (Map<String, Object>) inputs.get("variables")
              : new HashMap<>();

      // Get all available Grid instances
      List<GridInstance> instances = gridInstanceRegistry.getAvailableGridInstances();

      if (instances.isEmpty()) {
        // Return empty but valid GraphQL response instead of 503 error
        Map<String, Object> emptyResponse = createEmptyGraphqlResponse();
        return new HttpResponse()
            .addHeader("Content-Type", JSON_UTF_8)
            .setContent(utf8String(JSON.toJson(emptyResponse)));
      }

      // Query all Grid instances in parallel
      List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();

      for (GridInstance instance : instances) {
        CompletableFuture<Map<String, Object>> future =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return queryGridInstance(instance, query, variables);
                  } catch (Exception e) {
                    LOG.warning(
                        "Failed to query Grid instance "
                            + instance.getId()
                            + ": "
                            + e.getMessage());
                    return createErrorResponse(instance.getId(), e.getMessage());
                  }
                });
        futures.add(future);
      }

      // Wait for all responses and merge them
      try {
        List<Map<String, Object>> responses = new ArrayList<>();
        for (CompletableFuture<Map<String, Object>> future : futures) {
          responses.add(future.get(10, TimeUnit.SECONDS));
        }

        Map<String, Object> mergedResponse = mergeGraphqlResponses(responses);

        return new HttpResponse()
            .addHeader("Content-Type", JSON_UTF_8)
            .setContent(utf8String(JSON.toJson(mergedResponse)));

      } catch (Exception e) {
        LOG.severe("Failed to merge GraphQL responses: " + e.getMessage());
        return new HttpResponse()
            .setStatus(500)
            .setContent(
                utf8String(
                    "{\"errors\":[{\"message\":\"Failed to merge responses: "
                        + e.getMessage()
                        + "\"}]}"));
      }
    }
  }

  private Map<String, Object> queryGridInstance(
      GridInstance instance, String query, Map<String, Object> variables) {
    ClientConfig config =
        ClientConfig.defaultConfig()
            .baseUri(instance.getBaseUri())
            .readTimeout(java.time.Duration.ofSeconds(5))
            .connectionTimeout(java.time.Duration.ofSeconds(3));

    try (HttpClient client = httpClientFactory.createClient(config)) {
      HttpRequest graphqlRequest =
          new HttpRequest(org.openqa.selenium.remote.http.HttpMethod.POST, "/graphql");
      graphqlRequest.addHeader("Content-Type", "application/json");

      Map<String, Object> requestBody = new HashMap<>();
      requestBody.put("query", query);
      requestBody.put("variables", variables);

      graphqlRequest.setContent(utf8String(JSON.toJson(requestBody)));

      HttpResponse response = client.execute(graphqlRequest);

      if (response.getStatus() == 200) {
        String responseContent = Contents.string(response);
        Map<String, Object> responseData = JSON.toType(responseContent, Map.class);

        // Add instance metadata
        if (responseData.get("data") instanceof Map) {
          Map<String, Object> data = (Map<String, Object>) responseData.get("data");
          data.put("_instanceId", instance.getId());
          data.put("_instanceUri", instance.getBaseUri().toString());
        }

        return responseData;
      } else {
        LOG.warning("Grid instance " + instance.getId() + " returned HTTP " + response.getStatus());
        return createEmptyInstanceResponse(instance.getId());
      }
    } catch (Exception e) {
      LOG.warning("Grid instance " + instance.getId() + " is unreachable: " + e.getMessage());
      return createEmptyInstanceResponse(instance.getId());
    }
  }

  private Map<String, Object> createErrorResponse(String instanceId, String errorMessage) {
    Map<String, Object> error = new HashMap<>();
    error.put("message", "Grid instance " + instanceId + ": " + errorMessage);
    error.put("extensions", Map.of("instanceId", instanceId));

    Map<String, Object> response = new HashMap<>();
    response.put("errors", List.of(error));
    return response;
  }

  private Map<String, Object> createEmptyInstanceResponse(String instanceId) {
    Map<String, Object> emptyData = new HashMap<>();

    // Empty grid data for this instance
    Map<String, Object> emptyGrid = new HashMap<>();
    emptyGrid.put("totalSlots", 0);
    emptyGrid.put("nodeCount", 0);
    emptyGrid.put("maxSession", 0);
    emptyGrid.put("sessionCount", 0);
    emptyGrid.put("sessionQueueSize", 0);
    emptyGrid.put("_instanceId", instanceId);

    // Empty sessions and nodes
    Map<String, Object> emptySessionsInfo = new HashMap<>();
    emptySessionsInfo.put("sessions", new ArrayList<>());
    emptySessionsInfo.put("sessionQueueRequests", new ArrayList<>());

    Map<String, Object> emptyNodesInfo = new HashMap<>();
    emptyNodesInfo.put("nodes", new ArrayList<>());

    emptyData.put("grid", emptyGrid);
    emptyData.put("sessionsInfo", emptySessionsInfo);
    emptyData.put("nodesInfo", emptyNodesInfo);

    Map<String, Object> response = new HashMap<>();
    response.put("data", emptyData);

    return response;
  }

  private Map<String, Object> mergeGraphqlResponses(List<Map<String, Object>> responses) {
    Map<String, Object> mergedResponse = new HashMap<>();
    List<Object> allErrors = new ArrayList<>();

    // Initialize merged data structure according to schema
    Map<String, Object> mergedData = new HashMap<>();
    Map<String, Object> mergedGrid = new HashMap<>();
    Map<String, Object> mergedSessionsInfo = new HashMap<>();
    Map<String, Object> mergedNodesInfo = new HashMap<>();
    List<Object> allSessions = new ArrayList<>();
    List<Object> allNodes = new ArrayList<>();
    List<Object> allSessionQueueRequests = new ArrayList<>();

    int totalSlots = 0;
    int totalMaxSession = 0;
    int totalSessionCount = 0;
    int totalSessionQueueSize = 0;

    for (Map<String, Object> response : responses) {
      // Collect errors
      if (response.get("errors") instanceof List) {
        allErrors.addAll((List<?>) response.get("errors"));
      }

      // Merge data according to schema structure
      if (response.get("data") instanceof Map) {
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        // Merge grid data
        if (data.get("grid") instanceof Map) {
          Map<String, Object> grid = (Map<String, Object>) data.get("grid");
          totalSlots += getIntValue(grid, "totalSlots");
          totalMaxSession += getIntValue(grid, "maxSession");
          totalSessionCount += getIntValue(grid, "sessionCount");
          totalSessionQueueSize += getIntValue(grid, "sessionQueueSize");

          // Use first instance's version and URI as gateway representative
          if (mergedGrid.isEmpty()) {
            mergedGrid.put("uri", publicUri.toString());
            mergedGrid.put("version", version);
          }
        }

        // Merge sessions info
        if (data.get("sessionsInfo") instanceof Map) {
          Map<String, Object> sessionsInfo = (Map<String, Object>) data.get("sessionsInfo");
          if (sessionsInfo.get("sessions") instanceof List) {
            allSessions.addAll((List<?>) sessionsInfo.get("sessions"));
          }
          if (sessionsInfo.get("sessionQueueRequests") instanceof List) {
            allSessionQueueRequests.addAll((List<?>) sessionsInfo.get("sessionQueueRequests"));
          }
        }

        // Merge nodes info
        if (data.get("nodesInfo") instanceof Map) {
          Map<String, Object> nodesInfo = (Map<String, Object>) data.get("nodesInfo");
          if (nodesInfo.get("nodes") instanceof List) {
            allNodes.addAll((List<?>) nodesInfo.get("nodes"));
          }
        }

        // Handle single session query
        if (data.get("session") != null) {
          mergedData.put("session", data.get("session"));
        }
      }
    }

    // Build merged response according to schema
    mergedGrid.put("totalSlots", totalSlots);
    mergedGrid.put("nodeCount", allNodes.size());
    mergedGrid.put("maxSession", totalMaxSession);
    mergedGrid.put("sessionCount", totalSessionCount);
    mergedGrid.put("sessionQueueSize", totalSessionQueueSize);

    mergedSessionsInfo.put("sessions", allSessions);
    mergedSessionsInfo.put("sessionQueueRequests", allSessionQueueRequests);

    mergedNodesInfo.put("nodes", allNodes);

    mergedData.put("grid", mergedGrid);
    mergedData.put("sessionsInfo", mergedSessionsInfo);
    mergedData.put("nodesInfo", mergedNodesInfo);

    mergedResponse.put("data", mergedData);

    if (!allErrors.isEmpty()) {
      mergedResponse.put("errors", allErrors);
    }

    return mergedResponse;
  }

  private int getIntValue(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    return 0;
  }

  private Map<String, Object> createEmptyGraphqlResponse() {
    Map<String, Object> emptyData = new HashMap<>();

    // Empty grid data
    Map<String, Object> emptyGrid = new HashMap<>();
    emptyGrid.put("uri", publicUri.toString());
    emptyGrid.put("version", version);
    emptyGrid.put("totalSlots", 0);
    emptyGrid.put("nodeCount", 0);
    emptyGrid.put("maxSession", 0);
    emptyGrid.put("sessionCount", 0);
    emptyGrid.put("sessionQueueSize", 0);

    // Empty sessions info
    Map<String, Object> emptySessionsInfo = new HashMap<>();
    emptySessionsInfo.put("sessions", new ArrayList<>());
    emptySessionsInfo.put("sessionQueueRequests", new ArrayList<>());

    // Empty nodes info
    Map<String, Object> emptyNodesInfo = new HashMap<>();
    emptyNodesInfo.put("nodes", new ArrayList<>());

    emptyData.put("grid", emptyGrid);
    emptyData.put("sessionsInfo", emptySessionsInfo);
    emptyData.put("nodesInfo", emptyNodesInfo);

    Map<String, Object> response = new HashMap<>();
    response.put("data", emptyData);

    return response;
  }
}
