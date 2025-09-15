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

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.DISTRIBUTOR_ROLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.HTTPD_ROLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.NODE_ROLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.ROUTER_ROLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.SESSION_QUEUE_ROLE;
import static org.openqa.selenium.remote.http.Route.combine;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSet;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.openqa.selenium.BuildInfo;
import org.openqa.selenium.UsernameAndPassword;
import org.openqa.selenium.cli.CliCommand;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.grid.TemplateGridServerCommand;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.Role;
import org.openqa.selenium.grid.data.NodeDrainComplete;
import org.openqa.selenium.grid.distributor.Distributor;
import org.openqa.selenium.grid.distributor.config.DistributorOptions;
import org.openqa.selenium.grid.distributor.local.LocalDistributor;
import org.openqa.selenium.grid.graphql.GraphqlHandler;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.node.ProxyNodeWebsockets;
import org.openqa.selenium.grid.node.config.NodeOptions;
import org.openqa.selenium.grid.router.Router;
import org.openqa.selenium.grid.router.httpd.RouterOptions;
import org.openqa.selenium.grid.security.BasicAuthenticationFilter;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.security.SecretOptions;
import org.openqa.selenium.grid.server.BaseServerOptions;
import org.openqa.selenium.grid.server.EventBusOptions;
import org.openqa.selenium.grid.server.NetworkOptions;
import org.openqa.selenium.grid.server.Server;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.grid.sessionmap.local.LocalSessionMap;
import org.openqa.selenium.grid.sessionqueue.config.NewSessionQueueOptions;
import org.openqa.selenium.grid.sessionqueue.local.LocalNewSessionQueue;
import org.openqa.selenium.grid.web.CombinedHandler;
import org.openqa.selenium.grid.web.GridUiRoute;
import org.openqa.selenium.grid.web.RoutableHttpClientFactory;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.Routable;
import org.openqa.selenium.remote.http.Route;
import org.openqa.selenium.remote.tracing.Tracer;

@AutoService(CliCommand.class)
public class Standalone extends TemplateGridServerCommand {

  private static final Logger LOG = Logger.getLogger("selenium");
  private String instanceId;
  private String gatewayUrl;
  private HttpClient.Factory clientFactory;

  // Gateway registration retry mechanism
  private final AtomicBoolean registrationSuccessful = new AtomicBoolean(false);
  private final AtomicInteger registrationAttempts = new AtomicInteger(0);
  private ScheduledExecutorService registrationRetryExecutor;
  private static final int MAX_REGISTRATION_ATTEMPTS = 20;
  private static final long INITIAL_RETRY_DELAY_SECONDS = 5;
  private static final long MAX_RETRY_DELAY_SECONDS = 60;
  private static final Duration REGISTRATION_TIMEOUT = Duration.ofSeconds(30);

  @Override
  public String getName() {
    return "standalone";
  }

  @Override
  public String getDescription() {
    return "The selenium server, running everything in-process.";
  }

  @Override
  public Set<Role> getConfigurableRoles() {
    return ImmutableSet.of(
        DISTRIBUTOR_ROLE, HTTPD_ROLE, NODE_ROLE, ROUTER_ROLE, SESSION_QUEUE_ROLE);
  }

  @Override
  public Set<Object> getFlagObjects() {
    return Collections.singleton(new StandaloneFlags());
  }

  @Override
  protected String getSystemPropertiesConfigPrefix() {
    return "selenium";
  }

  @Override
  protected Config getDefaultConfig() {
    return new DefaultStandaloneConfig();
  }

  @Override
  protected Handlers createHandlers(Config config) {
    LoggingOptions loggingOptions = new LoggingOptions(config);
    Tracer tracer = loggingOptions.getTracer();

    EventBusOptions events = new EventBusOptions(config);
    EventBus bus = events.getEventBus();

    BaseServerOptions serverOptions = new BaseServerOptions(config);
    SecretOptions secretOptions = new SecretOptions(config);
    Secret registrationSecret = secretOptions.getRegistrationSecret();

    URI localhost = serverOptions.getExternalUri();
    URL localhostUrl;
    try {
      localhostUrl = localhost.toURL();
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException(e);
    }

    NetworkOptions networkOptions = new NetworkOptions(config);
    CombinedHandler combinedHandler = new CombinedHandler();
    this.clientFactory =
        new RoutableHttpClientFactory(
            localhostUrl, combinedHandler, networkOptions.getHttpClientFactory(tracer));

    this.instanceId = UUID.randomUUID().toString();

    SessionMap sessions = new LocalSessionMap(tracer, bus);
    combinedHandler.addHandler(sessions);

    DistributorOptions distributorOptions = new DistributorOptions(config);
    NewSessionQueueOptions newSessionRequestOptions = new NewSessionQueueOptions(config);
    LocalNewSessionQueue queue =
        new LocalNewSessionQueue(
            tracer,
            distributorOptions.getSlotMatcher(),
            newSessionRequestOptions.getSessionRequestTimeoutPeriod(),
            newSessionRequestOptions.getSessionRequestTimeout(),
            newSessionRequestOptions.getMaximumResponseDelay(),
            registrationSecret,
            newSessionRequestOptions.getBatchSize());
    combinedHandler.addHandler(queue);

    LocalDistributor distributor =
        new LocalDistributor(
            tracer,
            bus,
            this.clientFactory,
            sessions,
            queue,
            distributorOptions.getSlotSelector(),
            registrationSecret,
            distributorOptions.getHealthCheckInterval(),
            distributorOptions.shouldRejectUnsupportedCaps(),
            newSessionRequestOptions.getSessionRequestRetryInterval(),
            distributorOptions.getNewSessionThreadPoolSize(),
            distributorOptions.getSlotMatcher(),
            distributorOptions.getPurgeNodesInterval());
    combinedHandler.addHandler(distributor);

    Router router = new Router(tracer, this.clientFactory, sessions, queue, distributor);
    Routable routerWithSpecChecks = router.with(networkOptions.getSpecComplianceChecks());

    HttpHandler readinessCheck =
        req -> {
          boolean ready = sessions.isReady() && distributor.isReady() && bus.isReady();
          return new HttpResponse()
              .setStatus(ready ? HTTP_OK : HTTP_UNAVAILABLE)
              .setContent(Contents.utf8String("Standalone is " + ready));
        };

    GraphqlHandler graphqlHandler =
        new GraphqlHandler(
            tracer, distributor, queue, serverOptions.getExternalUri(), getFormattedVersion());

    RouterOptions routerOptions = new RouterOptions(config);
    String subPath = routerOptions.subPath();

    Routable appendRoute =
        Stream.of(
                baseRoute(subPath, combine(routerWithSpecChecks)),
                hubRoute(subPath, combine(routerWithSpecChecks)),
                graphqlRoute(subPath, () -> graphqlHandler))
            .reduce(Route::combine)
            .get();

    Routable httpHandler;
    if (routerOptions.disableUi()) {
      LOG.info("Grid UI has been disabled.");
      httpHandler = appendRoute;
    } else {
      Routable ui = new GridUiRoute(subPath);
      httpHandler = combine(ui, appendRoute);
    }

    UsernameAndPassword uap = secretOptions.getServerAuthentication();
    if (uap != null) {
      LOG.info("Requiring authentication to connect");
      httpHandler = httpHandler.with(new BasicAuthenticationFilter(uap.username(), uap.password()));
    }

    // Allow the liveness endpoint to be reached, since k8s doesn't make it easy to authenticate
    // these checks
    httpHandler = combine(httpHandler, Route.get("/readyz").to(() -> readinessCheck));
    Node node = createNode(config, bus, distributor, combinedHandler);

    return new Handlers(httpHandler, new ProxyNodeWebsockets(this.clientFactory, node, subPath)) {
      @Override
      public void close() {
        router.close();
        distributor.close();
        queue.close();
      }
    };
  }

  @Override
  protected void execute(Config config) {
    Require.nonNull("Config", config);

    config
        .get("server", "max-threads")
        .ifPresent(
            value ->
                LOG.log(
                    Level.WARNING,
                    () ->
                        "Support for max-threads flag is deprecated. The intent of the flag is to"
                            + " set the thread pool size in the Distributor. Please use"
                            + " newsession-threadpool-size flag instead."));

    Server<?> server = asServer(config).start();

    LOG.info(
        String.format(
            "Started Selenium Standalone %s: %s", getFormattedVersion(), server.getUrl()));

    // Register with gateway if configured
    this.gatewayUrl = config.get("router", "api-gateway").orElse(null);
    if (gatewayUrl != null) {
      String username = config.get("router", "api-gateway-username").orElse(null);
      String password = config.get("router", "api-gateway-password").orElse(null);
      if (username != null && password != null) {
        this.gatewayUrl = buildAuthUrl(gatewayUrl, username, password);
      }
    }
    LOG.info("Checking gateway configuration: " + gatewayUrl);
    if (gatewayUrl != null) {
      LOG.info("Gateway URL configured: " + gatewayUrl);

      // Initialize retry executor
      registrationRetryExecutor =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "gateway-registration-retry");
                t.setDaemon(true);
                return t;
              });

      // Start registration with retry mechanism
      startGatewayRegistrationWithRetry(server.getUrl());

      // Add shutdown hook to unregister and cleanup
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    shutdownRegistrationRetry();
                    unregisterFromGateway();
                  }));
    } else {
      LOG.info("No gateway URL configured");
    }
  }

  private String getFormattedVersion() {
    BuildInfo info = new BuildInfo();
    return String.format("%s (revision %s)", info.getReleaseLabel(), info.getBuildRevision());
  }

  private Node createNode(
      Config config, EventBus bus, Distributor distributor, CombinedHandler combinedHandler) {
    Node node = new NodeOptions(config).getNode();
    combinedHandler.addHandler(node);
    distributor.add(node);

    bus.addListener(
        NodeDrainComplete.listener(
            nodeId -> {
              if (!node.getId().equals(nodeId)) {
                return;
              }

              // Wait a beat before shutting down so the final response from the
              // node can escape.
              new Thread(
                      () -> {
                        try {
                          Thread.sleep(1000);
                        } catch (InterruptedException e) {
                          // Swallow, the next thing we're doing is shutting down
                        }
                        LOG.info("Shutting down");
                        System.exit(0);
                      },
                      "Standalone shutdown: " + nodeId)
                  .start();
            }));
    return node;
  }

  /**
   * Starts the Gateway registration process with retry mechanism. Attempts registration
   * immediately, then retries with exponential backoff if needed.
   */
  private void startGatewayRegistrationWithRetry(URL serverUrl) {
    LOG.info(
        String.format(
            "Starting Gateway registration for standalone %s with retry mechanism (max attempts:"
                + " %d)",
            instanceId, MAX_REGISTRATION_ATTEMPTS));

    // Attempt immediate registration
    attemptGatewayRegistration(serverUrl);
  }

  /** Attempts to register with Gateway with comprehensive error handling and retry logic. */
  private void attemptGatewayRegistration(URL serverUrl) {
    if (registrationSuccessful.get()) {
      LOG.fine("Standalone already successfully registered with Gateway");
      return;
    }

    int currentAttempt = registrationAttempts.incrementAndGet();

    if (currentAttempt > MAX_REGISTRATION_ATTEMPTS) {
      LOG.severe(
          String.format(
              "Failed to register standalone %s with gateway %s after %d attempts. Giving up.",
              instanceId, gatewayUrl, MAX_REGISTRATION_ATTEMPTS));
      return;
    }

    LOG.info(
        String.format(
            "Attempting Gateway registration (attempt %d/%d) for standalone %s",
            currentAttempt, MAX_REGISTRATION_ATTEMPTS, instanceId));

    try {
      String requestBody =
          String.format("{\"id\":\"%s\",\"url\":\"%s\"}", instanceId, serverUrl.toString());

      ClientConfig config =
          ClientConfig.defaultConfig()
              .baseUri(URI.create(gatewayUrl))
              .connectionTimeout(REGISTRATION_TIMEOUT)
              .readTimeout(REGISTRATION_TIMEOUT);

      try (HttpClient client = clientFactory.createClient(config)) {
        HttpRequest request = new HttpRequest(HttpMethod.POST, "/discovery");
        request.setContent(Contents.utf8String(requestBody));
        request.setHeader("Content-Type", "application/json");

        HttpResponse response = client.execute(request);

        if (response.getStatus() == 200) {
          registrationSuccessful.set(true);
          LOG.info(
              String.format(
                  "Successfully registered standalone %s with gateway %s (attempt %d/%d)",
                  instanceId, gatewayUrl, currentAttempt, MAX_REGISTRATION_ATTEMPTS));
          return;
        } else if (response.getStatus() == 409) {
          registrationSuccessful.set(true);
          LOG.info(
              String.format(
                  "Standalone instance already registered with gateway (409 conflict) - continuing"
                      + " (attempt %d/%d)",
                  currentAttempt, MAX_REGISTRATION_ATTEMPTS));
          return;
        } else {
          String responseBody = Contents.string(response);
          String errorMsg =
              String.format(
                  "Failed to register with gateway. Status: %d, Response: %s, attempt %d/%d",
                  response.getStatus(), responseBody, currentAttempt, MAX_REGISTRATION_ATTEMPTS);
          LOG.warning(errorMsg);

          if (currentAttempt < MAX_REGISTRATION_ATTEMPTS) {
            scheduleRetryAttempt(serverUrl, currentAttempt);
          }
        }
      }
    } catch (Exception e) {
      String errorMsg =
          String.format(
              "Failed to register with gateway (attempt %d/%d): %s",
              currentAttempt, MAX_REGISTRATION_ATTEMPTS, e.getMessage());
      LOG.log(Level.WARNING, errorMsg, e);

      if (currentAttempt < MAX_REGISTRATION_ATTEMPTS) {
        scheduleRetryAttempt(serverUrl, currentAttempt);
      }
    }
  }

  /** Schedules the next retry attempt with exponential backoff. */
  private void scheduleRetryAttempt(URL serverUrl, int currentAttempt) {
    if (registrationRetryExecutor == null || registrationRetryExecutor.isShutdown()) {
      LOG.warning("Registration retry executor is not available for scheduling retry");
      return;
    }

    // Calculate exponential backoff delay: min(INITIAL_DELAY * 2^(attempt-1), MAX_DELAY)
    long delaySeconds =
        Math.min(
            INITIAL_RETRY_DELAY_SECONDS * (1L << (currentAttempt - 1)), MAX_RETRY_DELAY_SECONDS);

    LOG.info(
        String.format(
            "Scheduling Gateway registration retry in %d seconds (attempt %d/%d)",
            delaySeconds, currentAttempt + 1, MAX_REGISTRATION_ATTEMPTS));

    registrationRetryExecutor.schedule(
        () -> attemptGatewayRegistration(serverUrl), delaySeconds, TimeUnit.SECONDS);
  }

  /** Shuts down the registration retry mechanism gracefully. */
  private void shutdownRegistrationRetry() {
    if (registrationRetryExecutor != null && !registrationRetryExecutor.isShutdown()) {
      LOG.info("Shutting down Gateway registration retry mechanism");
      registrationRetryExecutor.shutdown();
      try {
        if (!registrationRetryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          registrationRetryExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        registrationRetryExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  private void unregisterFromGateway() {
    if (gatewayUrl == null || instanceId == null) {
      return;
    }

    try {
      ClientConfig config = ClientConfig.defaultConfig().baseUri(URI.create(gatewayUrl));
      try (HttpClient client = clientFactory.createClient(config)) {
        HttpRequest request =
            new HttpRequest(
                HttpMethod.DELETE,
                "/discovery?url=" + java.net.URLEncoder.encode(gatewayUrl, "UTF-8"));

        HttpResponse response = client.execute(request);
        if (response.getStatus() == 200) {
          LOG.info(
              String.format("Successfully unregistered standalone %s from gateway", instanceId));
        } else {
          LOG.warning(
              String.format("Failed to unregister from gateway. Status: %d", response.getStatus()));
        }
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to unregister from gateway: " + e.getMessage(), e);
    }
  }

  private String buildAuthUrl(String baseUrl, String username, String password) {
    try {
      URI uri = URI.create(baseUrl);
      String userInfo = username + ":" + password;
      return new URI(
              uri.getScheme(),
              userInfo,
              uri.getHost(),
              uri.getPort(),
              uri.getPath(),
              uri.getQuery(),
              uri.getFragment())
          .toString();
    } catch (Exception e) {
      LOG.warning("Failed to build authenticated URL: " + e.getMessage());
      return baseUrl;
    }
  }
}
