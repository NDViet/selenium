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

package org.openqa.selenium.grid.gateway.httpd;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.GATEWAY_ROLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.HTTPD_ROLE;
import static org.openqa.selenium.remote.http.Route.combine;
import static org.openqa.selenium.remote.http.Route.get;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.logging.Logger;
import org.openqa.selenium.BuildInfo;
import org.openqa.selenium.UsernameAndPassword;
import org.openqa.selenium.cli.CliCommand;
import org.openqa.selenium.grid.TemplateGridServerCommand;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.MapConfig;
import org.openqa.selenium.grid.config.Role;
import org.openqa.selenium.grid.gateway.GatewayOptions;
import org.openqa.selenium.grid.gateway.GridLoadBalancerFactory;
import org.openqa.selenium.grid.gateway.ProxyWebsocketsIntoGateway;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.security.BasicAuthenticationFilter;
import org.openqa.selenium.grid.security.SecretOptions;
import org.openqa.selenium.grid.server.BaseServerOptions;
import org.openqa.selenium.grid.server.NetworkOptions;
import org.openqa.selenium.grid.server.Server;
import org.openqa.selenium.grid.web.GridUiRoute;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.Filter;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.Routable;
import org.openqa.selenium.remote.http.Route;
import org.openqa.selenium.remote.tracing.Tracer;

@AutoService(CliCommand.class)
public class GatewayServer extends TemplateGridServerCommand {

  private static final Logger LOG = Logger.getLogger(GatewayServer.class.getName());

  @Override
  public String getName() {
    return "gateway";
  }

  @Override
  public String getDescription() {
    return "Creates a gateway to front the selenium grid with enhanced routing and load balancing.";
  }

  @Override
  public Set<Role> getConfigurableRoles() {
    return ImmutableSet.of(GATEWAY_ROLE, HTTPD_ROLE);
  }

  @Override
  public Set<Object> getFlagObjects() {
    return Set.of(new GatewayOptions());
  }

  @Override
  protected String getSystemPropertiesConfigPrefix() {
    return "gateway";
  }

  @Override
  protected Config getDefaultConfig() {
    return new MapConfig(
        ImmutableMap.of(
            "server", ImmutableMap.of("port", 4444),
            "network", ImmutableMap.of("relax-checks", true)));
  }

  @Override
  protected Handlers createHandlers(Config config) {
    LoggingOptions loggingOptions = new LoggingOptions(config);
    Tracer tracer = loggingOptions.getTracer();

    NetworkOptions networkOptions = new NetworkOptions(config);
    HttpClient.Factory clientFactory = networkOptions.getHttpClientFactory(tracer);

    BaseServerOptions serverOptions = new BaseServerOptions(config);
    SecretOptions secretOptions = new SecretOptions(config);

    GatewayOptions gatewayOptions = new GatewayOptions(config);
    String subPath = gatewayOptions.getSubPath();

    // Create Gateway with discovery endpoints
    org.openqa.selenium.grid.gateway.GridLoadBalancer loadBalancer =
        GridLoadBalancerFactory.create(tracer, clientFactory, gatewayOptions, serverOptions);

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

    // Create Gateway wrapper with discovery endpoints
    org.openqa.selenium.grid.gateway.Gateway gateway =
        new org.openqa.selenium.grid.gateway.Gateway(
            tracer,
            clientFactory,
            loadBalancer.getGridInstanceRegistry(),
            null, // routing rules config
            3, // max retry attempts
            gatewayOptions.getRequestTimeout(), // Use configurable request timeout
            serverOptions.getExternalUri(),
            "gateway-" + System.currentTimeMillis());

    // Create a custom filter that normalizes Content-Type headers for better client compatibility
    Filter contentTypeNormalizer =
        httpHandler ->
            req -> {
              // If the request has Content-Type: application/json without charset, add
              // charset=utf-8
              String contentType = req.getHeader("Content-Type");
              if (contentType != null && contentType.equals("application/json")) {
                // Create a new request with the normalized Content-Type header
                HttpRequest normalizedRequest = new HttpRequest(req.getMethod(), req.getUri());

                // Copy all headers except Content-Type
                req.getHeaderNames()
                    .forEach(
                        name -> {
                          if (!"Content-Type".equalsIgnoreCase(name)) {
                            req.getHeaders(name)
                                .forEach(value -> normalizedRequest.addHeader(name, value));
                          }
                        });

                // Add the normalized Content-Type header
                normalizedRequest.addHeader("Content-Type", "application/json; charset=utf-8");

                // Copy the request body
                normalizedRequest.setContent(req.getContent());

                return httpHandler.execute(normalizedRequest);
              }

              // For all other requests, pass through unchanged
              return httpHandler.execute(req);
            };

    // Apply the content type normalizer first, then spec compliance checks
    Filter combinedFilter = contentTypeNormalizer.andThen(networkOptions.getSpecComplianceChecks());
    Routable gatewayWithSpecChecks = gateway.with(combinedFilter);

    Routable route;
    if (gatewayOptions.isDisableUi()) {
      LOG.info("Gateway UI has been disabled.");
      route = gatewayWithSpecChecks;
    } else {
      Routable ui = new GridUiRoute(subPath);
      String redirectLocation = subPath.isEmpty() ? "/ui/index.html" : subPath + "/ui/index.html";
      route =
          combine(
              get("/")
                  .to(
                      () ->
                          req -> {
                            HttpResponse response = new HttpResponse();
                            response.setStatus(302);
                            response.setHeader("Location", redirectLocation);
                            return response;
                          }),
              ui,
              gatewayWithSpecChecks);
    }

    UsernameAndPassword uap = secretOptions.getServerAuthentication();
    if (uap != null) {
      LOG.info("Requiring authentication to connect");
      route = route.with(new BasicAuthenticationFilter(uap.username(), uap.password()));
    } else {
      LOG.info("No authentication configured - allowing unauthenticated access");
    }

    HttpHandler readinessCheck =
        req -> {
          boolean ready = gateway.isReady();
          return new HttpResponse()
              .setStatus(ready ? HTTP_OK : HTTP_UNAVAILABLE)
              .setContent(Contents.utf8String("Gateway is " + ready));
        };

    // Since k8s doesn't make it easy to do an authenticated liveness probe, allow unauthenticated
    // access to it.
    Routable routeWithLiveness = Route.combine(route, get("/readyz").to(() -> readinessCheck));

    ProxyWebsocketsIntoGateway websocketProxy =
        new ProxyWebsocketsIntoGateway(clientFactory, loadBalancer.getGridInstanceRegistry());

    System.out.println("🔧 Creating Handlers with WebSocket proxy: " + websocketProxy);
    LOG.severe("🔧 Creating Handlers with WebSocket proxy: " + websocketProxy);

    return new Handlers(routeWithLiveness, websocketProxy) {
      @Override
      public void close() {
        gateway.close();
      }
    };
  }

  @Override
  protected void execute(Config config) {
    System.out.println("🚀 GatewayServer.execute() called");
    LOG.severe("🚀 GatewayServer.execute() called");

    Require.nonNull("Config", config);

    Server<?> server = asServer(config).start();

    System.out.println("🚀 Gateway started at: " + server.getUrl());
    LOG.info(String.format("Started Selenium Gateway %s: %s", getServerVersion(), server.getUrl()));
  }

  private String getServerVersion() {
    BuildInfo info = new BuildInfo();
    return String.format("%s (revision %s)", info.getReleaseLabel(), info.getBuildRevision());
  }
}
