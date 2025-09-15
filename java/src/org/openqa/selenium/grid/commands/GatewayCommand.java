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

package org.openqa.selenium.grid.commands;

import static org.openqa.selenium.grid.config.StandardGridRoles.GATEWAY_ROLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.HTTPD_ROLE;

import com.google.auto.service.AutoService;
import java.util.Set;
import java.util.logging.Logger;
import org.openqa.selenium.cli.CliCommand;
import org.openqa.selenium.grid.TemplateGridServerCommand;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.Role;
import org.openqa.selenium.grid.gateway.Gateway;
import org.openqa.selenium.grid.gateway.GatewayOptions;
import org.openqa.selenium.grid.gateway.GridLoadBalancer;
import org.openqa.selenium.grid.gateway.GridLoadBalancerFactory;
import org.openqa.selenium.grid.gateway.ProxyWebsocketsIntoGateway;
import org.openqa.selenium.grid.gateway.RoutingRulesConfig;
import org.openqa.selenium.grid.log.LoggingFlags;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.server.BaseServerOptions;
import org.openqa.selenium.grid.server.NetworkOptions;
import org.openqa.selenium.grid.server.Server;
import org.openqa.selenium.grid.web.GridUiRoute;
import org.openqa.selenium.netty.server.NettyServer;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.Route;
import org.openqa.selenium.remote.tracing.Tracer;

/**
 * Command-line interface for the Selenium Grid Gateway.
 *
 * <p>The Gateway provides enhanced routing, load balancing, and service discovery capabilities for
 * multiple Selenium Grid instances. It supports YAML-based routing rules, weighted distribution,
 * and advanced session management.
 *
 * <p>Usage:
 *
 * <pre>
 * java -jar selenium-server-4.x.x.jar gateway \
 *   --grid-instances http://grid1:4444,http://grid2:4444 \
 *   --routing-rules-file routing-rules.yaml \
 *   --load-balancing-strategy GREEDY \
 *   --enable-discovery-endpoints
 * </pre>
 */
@AutoService(CliCommand.class)
public class GatewayCommand extends TemplateGridServerCommand {

  private static final Logger LOG = Logger.getLogger(GatewayCommand.class.getName());

  @Override
  public String getName() {
    return "gateway";
  }

  @Override
  public String getDescription() {
    return "Starts a Selenium Grid Gateway with enhanced routing, load balancing, and service"
        + " discovery.";
  }

  @Override
  public Set<Role> getConfigurableRoles() {
    return Set.of(GATEWAY_ROLE, HTTPD_ROLE);
  }

  @Override
  public Set<Object> getFlagObjects() {
    return Set.of(new GatewayOptions(), new LoggingFlags());
  }

  @Override
  protected String getSystemPropertiesConfigPrefix() {
    return "selenium";
  }

  @Override
  protected Config getDefaultConfig() {
    return new DefaultGatewayConfig();
  }

  @Override
  protected void execute(Config config) {
    Handlers handlers = createHandlers(config);

    BaseServerOptions serverOptions = new BaseServerOptions(config);
    Server<?> server =
        new NettyServer(serverOptions, handlers.httpHandler, handlers.websocketHandler);

    server.start();

    LOG.info("Selenium Grid Gateway started successfully");
    LOG.info("Listening on: " + server.getUrl());
  }

  @Override
  protected Handlers createHandlers(Config config) {
    LoggingOptions loggingOptions = new LoggingOptions(config);
    Tracer tracer = loggingOptions.getTracer();

    GatewayOptions gatewayOptions = new GatewayOptions(config);
    BaseServerOptions serverOptions = new BaseServerOptions(config);
    String subPath = gatewayOptions.getSubPath();

    LOG.info("Starting Selenium Grid Gateway");
    LOG.info("Grid instances: " + gatewayOptions.getGridInstanceUris());
    LOG.info("Load balancing strategy: " + gatewayOptions.getLoadBalancingStrategy());
    LOG.info("Routing rules enabled: " + gatewayOptions.isRoutingRulesEnabled());
    LOG.info("Discovery endpoints enabled: " + gatewayOptions.isDiscoveryEndpointsEnabled());

    if (gatewayOptions.isRoutingRulesEnabled() && gatewayOptions.getRoutingRulesFile() != null) {
      LOG.info("Routing rules file: " + gatewayOptions.getRoutingRulesFile());
    }

    NetworkOptions networkOptions = new NetworkOptions(config);
    HttpClient.Factory httpClientFactory = networkOptions.getHttpClientFactory(tracer);

    // Create the underlying GridLoadBalancer
    GridLoadBalancer loadBalancer;
    try {
      loadBalancer =
          GridLoadBalancerFactory.create(tracer, httpClientFactory, gatewayOptions, serverOptions);
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to create Grid Load Balancer for Gateway: " + e.getMessage(), e);
    }

    // Register predefined Grid instances
    java.util.List<String> predefinedInstances = gatewayOptions.getGridInstances();
    if (!predefinedInstances.isEmpty()) {
      LOG.info("Registering " + predefinedInstances.size() + " predefined Grid instances");
      for (int i = 0; i < predefinedInstances.size(); i++) {
        String instanceUrl = predefinedInstances.get(i);
        try {
          java.net.URI instanceUri = java.net.URI.create(instanceUrl);
          String instanceId = "gateway-predefined-" + (i + 1);
          loadBalancer.getGridInstanceRegistry().addGridInstance(instanceId, instanceUri);
          LOG.info("Registered predefined Grid instance: " + instanceId + " at " + instanceUrl);
        } catch (Exception e) {
          LOG.warning(
              "Failed to register predefined Grid instance " + instanceUrl + ": " + e.getMessage());
        }
      }
    }

    // Create the Gateway wrapper
    Gateway gateway;
    try {
      // Load routing rules configuration
      RoutingRulesConfig routingRulesConfig = gatewayOptions.loadRoutingRulesConfig();

      // Extract parameters from options for Gateway constructor
      gateway =
          new Gateway(
              tracer,
              httpClientFactory,
              loadBalancer.getGridInstanceRegistry(),
              routingRulesConfig,
              3, // maxRetryAttempts - default value
              java.time.Duration.ofSeconds(gatewayOptions.getDiscoveryRegistrationTimeoutSeconds()),
              serverOptions.getExternalUri(),
              "gateway-" + System.currentTimeMillis() // version/instanceId
              );
    } catch (Exception e) {
      throw new RuntimeException("Failed to create Gateway: " + e.getMessage(), e);
    }

    // Create GraphQL handler directly
    org.openqa.selenium.grid.gateway.GatewayGraphqlHandler gatewayGraphqlHandler =
        new org.openqa.selenium.grid.gateway.GatewayGraphqlHandler(
            tracer,
            gateway.getGridLoadBalancer().getGridInstanceRegistry(),
            httpClientFactory,
            serverOptions.getExternalUri(),
            "gateway-" + System.currentTimeMillis());

    // Create routes with sub-path support exactly like RouterServer
    java.util.stream.Stream<org.openqa.selenium.remote.http.Routable> appendRoute =
        java.util.stream.Stream.of(
            baseRoute(subPath, Route.combine(gateway)),
            hubRoute(subPath, Route.combine(gateway)),
            graphqlRoute(subPath, () -> gatewayGraphqlHandler));

    org.openqa.selenium.remote.http.Routable gatewayRoutes =
        appendRoute.reduce(Route::combine).get();

    // Add Grid UI integration exactly like RouterServer
    HttpHandler httpHandler;
    if (gatewayOptions.isDisableUi()) {
      LOG.info("Gateway UI has been disabled.");
      httpHandler = gatewayRoutes;
    } else {
      GridUiRoute ui = new GridUiRoute(subPath);
      httpHandler = Route.combine(ui, gatewayRoutes);
      String uiPath = subPath.isEmpty() ? "/ui/" : subPath + "/ui/";
      LOG.info("Gateway UI enabled at " + uiPath);
    }

    // Add basic authentication if username and password are configured
    String username = gatewayOptions.getUsername();
    String password = gatewayOptions.getPassword();
    if (username != null
        && !username.trim().isEmpty()
        && password != null
        && !password.trim().isEmpty()) {
      LOG.info("Requiring authentication to connect");
      httpHandler =
          ((org.openqa.selenium.remote.http.Routable) httpHandler)
              .with(
                  new org.openqa.selenium.grid.security.BasicAuthenticationFilter(
                      username, password));
    }

    // Create WebSocket proxy for Gateway
    ProxyWebsocketsIntoGateway websocketProxy =
        new ProxyWebsocketsIntoGateway(
            httpClientFactory,
            loadBalancer.getSessionGridMapping(),
            loadBalancer.getGridInstanceRegistry());

    LOG.info("Gateway WebSocket proxy created and enabled for session-based routing");
    LOG.info("WebSocket proxy supports Gateway routing rules and enhanced session management");

    return new GatewayHandlers(httpHandler, websocketProxy, gateway);
  }

  private static class GatewayHandlers extends Handlers {
    private final Gateway gateway;

    public GatewayHandlers(
        HttpHandler httpHandler, ProxyWebsocketsIntoGateway websocketProxy, Gateway gateway) {
      super(httpHandler, websocketProxy);
      this.gateway = gateway;

      LOG.info(
          "GatewayHandlers created with WebSocket proxy: "
              + (websocketProxy != null ? "enabled" : "disabled"));
    }

    @Override
    public void close() {
      try {
        gateway.close();
      } catch (Exception e) {
        LOG.severe("Error closing Gateway: " + e.getMessage());
      }
    }
  }

  /** Default configuration for the Gateway command. */
  private static class DefaultGatewayConfig implements Config {
    @Override
    public java.util.Optional<java.util.List<String>> getAll(String section, String option) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Set<String> getSectionNames() {
      return java.util.Collections.emptySet();
    }

    @Override
    public java.util.Set<String> getOptions(String section) {
      return java.util.Collections.emptySet();
    }
  }
}
