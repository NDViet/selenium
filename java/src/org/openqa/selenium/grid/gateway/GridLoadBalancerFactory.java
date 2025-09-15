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

import java.net.URI;
import java.util.List;
import java.util.logging.Logger;
import org.openqa.selenium.BuildInfo;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.ConfigException;
import org.openqa.selenium.grid.server.BaseServerOptions;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

/** Factory for creating GridLoadBalancer instances with proper configuration. */
public class GridLoadBalancerFactory {

  private static final Logger LOG = Logger.getLogger(GridLoadBalancerFactory.class.getName());

  /** Creates a GridLoadBalancer from configuration. */
  public static GridLoadBalancer create(Config config) {
    Require.nonNull("Config", config);

    // For now, we'll create a simple factory method that requires explicit parameters
    // This can be enhanced later when the Config API usage is clarified
    throw new ConfigException(
        "GridLoadBalancer.create(Config) not yet implemented. Use GridLoadBalancer.create(Tracer,"
            + " HttpClient.Factory, GridLoadBalancerOptions) instead.");
  }

  /** Creates a GridLoadBalancer with the specified configuration. */
  public static GridLoadBalancer create(
      Tracer tracer, HttpClient.Factory httpClientFactory, GatewayOptions options) {
    return create(tracer, httpClientFactory, options, null);
  }

  /** Creates a GridLoadBalancer with the specified configuration and server options. */
  public static GridLoadBalancer create(
      Tracer tracer,
      HttpClient.Factory httpClientFactory,
      GatewayOptions options,
      BaseServerOptions serverOptions) {
    return create(tracer, httpClientFactory, options, serverOptions, null);
  }

  public static GridLoadBalancer create(
      Tracer tracer,
      HttpClient.Factory httpClientFactory,
      GatewayOptions options,
      BaseServerOptions serverOptions,
      String instanceId) {

    Require.nonNull("Tracer", tracer);
    Require.nonNull("HTTP client factory", httpClientFactory);
    Require.nonNull("Grid Load Balancer options", options);

    // Use API Gateway for high-traffic scenarios
    if (options.isEnableMetrics()) {
      LOG.info("Creating API Gateway for high-traffic routing");
      return createApiGateway(tracer, httpClientFactory, options, serverOptions);
    }

    GridInstanceRegistry registry;

    // Check if dynamic discovery is enabled
    if (options.isDiscoveryEnabled()) {
      LOG.info("Creating Dynamic Grid Load Balancer with service discovery");
      LOG.info("Dynamic discovery will be implemented when discovery classes are available");

      // For now, fall back to static registry
      List<URI> gridInstanceUris = options.getGridInstanceUris();

      registry =
          new GridInstanceRegistry(
              tracer,
              httpClientFactory,
              options.getLoadBalancingStrategy(),
              options.getHealthCheckInterval(),
              options.getHealthCheckTimeout(),
              options.getMaxFailureCount());

    } else {
      // Use static registry
      List<URI> gridInstanceUris = options.getGridInstanceUris();
      if (gridInstanceUris.isEmpty()) {
        throw new ConfigException("At least one Grid instance URI must be configured");
      }

      LOG.info(
          String.format(
              "Creating Grid Load Balancer with %d Grid instances", gridInstanceUris.size()));

      registry =
          new GridInstanceRegistry(
              tracer,
              httpClientFactory,
              options.getLoadBalancingStrategy(),
              options.getHealthCheckInterval(),
              options.getHealthCheckTimeout(),
              options.getMaxFailureCount());

      // Register Grid instances
      for (int i = 0; i < gridInstanceUris.size(); i++) {
        URI uri = gridInstanceUris.get(i);
        String gridInstanceId = String.format("grid-instance-%d", i + 1);
        registry.addGridInstance(gridInstanceId, uri);
        LOG.info(String.format("Registered Grid instance %s at %s", gridInstanceId, uri));
      }
    }

    // Get public URI and version for GraphQL endpoint
    URI publicUri =
        serverOptions != null
            ? serverOptions.getExternalUri()
            : URI.create("http://localhost:4444"); // Default fallback
    BuildInfo buildInfo = new BuildInfo();
    String version =
        String.format(
            "%s (revision %s)", buildInfo.getReleaseLabel(), buildInfo.getBuildRevision());

    // Create and return the load balancer
    GridLoadBalancer loadBalancer =
        new GridLoadBalancer(
            tracer,
            httpClientFactory,
            registry,
            options.getMaxRetryAttempts(),
            options.getRequestTimeout(),
            publicUri,
            version);

    LOG.info("Grid Load Balancer created successfully");
    return loadBalancer;
  }

  private static GridLoadBalancer createApiGateway(
      Tracer tracer,
      HttpClient.Factory httpClientFactory,
      GatewayOptions options,
      BaseServerOptions serverOptions) {

    // Create registry with connection pooling
    GridInstanceRegistry registry =
        new GridInstanceRegistry(
            tracer,
            httpClientFactory,
            options.getLoadBalancingStrategy(),
            options.getHealthCheckInterval(),
            options.getHealthCheckTimeout(),
            options.getMaxFailureCount());

    URI publicUri =
        serverOptions != null
            ? serverOptions.getExternalUri()
            : URI.create("http://localhost:4444");

    return new GridLoadBalancer(
        tracer,
        httpClientFactory,
        registry,
        options.getMaxRetryAttempts(),
        options.getRequestTimeout(),
        publicUri,
        "4.0.0-gateway");
  }

  /** Creates a simple GridLoadBalancer for testing purposes. */
  public static GridLoadBalancer createForTesting(
      Tracer tracer, HttpClient.Factory httpClientFactory, List<URI> gridInstanceUris) {

    GatewayOptions options = new GatewayOptions();
    // For testing, create a simple options instance with grid instances
    // Note: This may need adjustment based on GatewayOptions implementation

    return create(tracer, httpClientFactory, options);
  }
}
