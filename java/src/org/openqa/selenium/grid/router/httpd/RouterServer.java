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

package org.openqa.selenium.grid.router.httpd;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.DISTRIBUTOR_ROLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.HTTPD_ROLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.ROUTER_ROLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.SESSION_MAP_ROLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.SESSION_QUEUE_ROLE;
import static org.openqa.selenium.net.Urls.fromUri;
import static org.openqa.selenium.remote.http.Route.combine;
import static org.openqa.selenium.remote.http.Route.get;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.openqa.selenium.grid.TemplateGridServerCommand;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.MapConfig;
import org.openqa.selenium.grid.config.Role;
import org.openqa.selenium.grid.distributor.Distributor;
import org.openqa.selenium.grid.distributor.config.DistributorOptions;
import org.openqa.selenium.grid.distributor.remote.RemoteDistributor;
import org.openqa.selenium.grid.graphql.GraphqlHandler;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.router.ProxyWebsocketsIntoGrid;
import org.openqa.selenium.grid.router.Router;
import org.openqa.selenium.grid.security.BasicAuthenticationFilter;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.security.SecretOptions;
import org.openqa.selenium.grid.server.BaseServerOptions;
import org.openqa.selenium.grid.server.NetworkOptions;
import org.openqa.selenium.grid.server.Server;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.grid.sessionmap.config.SessionMapOptions;
import org.openqa.selenium.grid.sessionqueue.NewSessionQueue;
import org.openqa.selenium.grid.sessionqueue.config.NewSessionQueueOptions;
import org.openqa.selenium.grid.sessionqueue.remote.RemoteNewSessionQueue;
import org.openqa.selenium.grid.web.GridUiRoute;
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
public class RouterServer extends TemplateGridServerCommand {

  private static final Logger LOG = Logger.getLogger(RouterServer.class.getName());
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
    return "router";
  }

  @Override
  public String getDescription() {
    return "Creates a router to front the selenium grid.";
  }

  @Override
  public Set<Role> getConfigurableRoles() {
    return ImmutableSet.of(
        DISTRIBUTOR_ROLE, HTTPD_ROLE, ROUTER_ROLE, SESSION_MAP_ROLE, SESSION_QUEUE_ROLE);
  }

  @Override
  public Set<Object> getFlagObjects() {
    return Collections.emptySet();
  }

  @Override
  protected String getSystemPropertiesConfigPrefix() {
    return "router";
  }

  @Override
  protected Config getDefaultConfig() {
    return new MapConfig(ImmutableMap.of("server", ImmutableMap.of("port", 4444)));
  }

  @Override
  protected Handlers createHandlers(Config config) {
    LoggingOptions loggingOptions = new LoggingOptions(config);
    Tracer tracer = loggingOptions.getTracer();

    NetworkOptions networkOptions = new NetworkOptions(config);
    this.clientFactory = networkOptions.getHttpClientFactory(tracer);

    BaseServerOptions serverOptions = new BaseServerOptions(config);
    SecretOptions secretOptions = new SecretOptions(config);
    Secret secret = secretOptions.getRegistrationSecret();

    SessionMapOptions sessionsOptions = new SessionMapOptions(config);
    SessionMap sessions = sessionsOptions.getSessionMap();

    NewSessionQueueOptions newSessionQueueOptions = new NewSessionQueueOptions(config);
    URL sessionQueueUrl = fromUri(newSessionQueueOptions.getSessionQueueUri());
    Duration sessionRequestTimeout = newSessionQueueOptions.getSessionRequestTimeout();
    ClientConfig httpClientConfig =
        ClientConfig.defaultConfig().baseUrl(sessionQueueUrl).readTimeout(sessionRequestTimeout);
    NewSessionQueue queue =
        new RemoteNewSessionQueue(tracer, clientFactory.createClient(httpClientConfig), secret);

    DistributorOptions distributorOptions = new DistributorOptions(config);
    URL distributorUrl = fromUri(distributorOptions.getDistributorUri());
    Distributor distributor = new RemoteDistributor(tracer, clientFactory, distributorUrl, secret);

    GraphqlHandler graphqlHandler =
        new GraphqlHandler(
            tracer, distributor, queue, serverOptions.getExternalUri(), getServerVersion());

    RouterOptions routerOptions = new RouterOptions(config);
    String subPath = routerOptions.subPath();
    this.gatewayUrl = routerOptions.getApiGateway();
    if (gatewayUrl != null) {
      String username = routerOptions.getApiGatewayUsername();
      String password = routerOptions.getApiGatewayPassword();
      if (username != null && password != null) {
        this.gatewayUrl = buildAuthUrl(gatewayUrl, username, password);
      }
    }
    this.instanceId = UUID.randomUUID().toString();

    Router router = new Router(tracer, clientFactory, sessions, queue, distributor);
    Routable routerWithSpecChecks = router.with(networkOptions.getSpecComplianceChecks());

    Routable appendRoute =
        Stream.of(
                baseRoute(subPath, combine(routerWithSpecChecks)),
                hubRoute(subPath, combine(routerWithSpecChecks)),
                graphqlRoute(subPath, () -> graphqlHandler))
            .reduce(Route::combine)
            .get();

    Routable route;
    if (routerOptions.disableUi()) {
      LOG.info("Grid UI has been disabled.");
      route = appendRoute;
    } else {
      Routable ui = new GridUiRoute(subPath);
      route = combine(ui, appendRoute);
    }

    UsernameAndPassword uap = secretOptions.getServerAuthentication();
    if (uap != null) {
      LOG.info("Requiring authentication to connect");
      route = route.with(new BasicAuthenticationFilter(uap.username(), uap.password()));
    }

    HttpHandler readinessCheck =
        req -> {
          boolean ready = router.isReady();
          return new HttpResponse()
              .setStatus(ready ? HTTP_OK : HTTP_UNAVAILABLE)
              .setContent(Contents.utf8String("Router is " + ready));
        };

    // Since k8s doesn't make it easy to do an authenticated liveness probe, allow unauthenticated
    // access to it.
    Routable routeWithLiveness = Route.combine(route, get("/readyz").to(() -> readinessCheck));

    return new Handlers(
        routeWithLiveness, new ProxyWebsocketsIntoGrid(this.clientFactory, sessions)) {
      @Override
      public void close() {
        router.close();
        if (sessions instanceof Closeable) {
          try {
            ((Closeable) sessions).close();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
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

    LOG.info(String.format("Started Selenium Router %s: %s", getServerVersion(), server.getUrl()));

    // Register with gateway if configured
    if (gatewayUrl != null) {
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
    }
  }

  private String getServerVersion() {
    BuildInfo info = new BuildInfo();
    return String.format("%s (revision %s)", info.getReleaseLabel(), info.getBuildRevision());
  }

  /**
   * Starts the Gateway registration process with retry mechanism. Attempts registration
   * immediately, then retries with exponential backoff if needed.
   */
  private void startGatewayRegistrationWithRetry(URL routerUrl) {
    LOG.info(
        String.format(
            "Starting Gateway registration for router %s with retry mechanism (max attempts: %d)",
            instanceId, MAX_REGISTRATION_ATTEMPTS));

    // Attempt immediate registration
    attemptGatewayRegistration(routerUrl);
  }

  /** Attempts to register with Gateway with comprehensive error handling and retry logic. */
  private void attemptGatewayRegistration(URL routerUrl) {
    if (registrationSuccessful.get()) {
      LOG.fine("Router already successfully registered with Gateway");
      return;
    }

    int currentAttempt = registrationAttempts.incrementAndGet();

    if (currentAttempt > MAX_REGISTRATION_ATTEMPTS) {
      LOG.severe(
          String.format(
              "Failed to register router %s with gateway %s after %d attempts. Giving up.",
              instanceId, gatewayUrl, MAX_REGISTRATION_ATTEMPTS));
      return;
    }

    LOG.info(
        String.format(
            "Attempting Gateway registration (attempt %d/%d) for router %s",
            currentAttempt, MAX_REGISTRATION_ATTEMPTS, instanceId));

    try {
      String requestBody =
          String.format("{\"id\":\"%s\",\"url\":\"%s\"}", instanceId, routerUrl.toString());

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
                  "Successfully registered router %s with gateway %s (attempt %d/%d)",
                  instanceId, gatewayUrl, currentAttempt, MAX_REGISTRATION_ATTEMPTS));
          return;
        } else {
          String errorMsg =
              String.format(
                  "Failed to register with gateway. Status: %d, attempt %d/%d",
                  response.getStatus(), currentAttempt, MAX_REGISTRATION_ATTEMPTS);
          LOG.warning(errorMsg);

          if (currentAttempt < MAX_REGISTRATION_ATTEMPTS) {
            scheduleRetryAttempt(routerUrl, currentAttempt);
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
        scheduleRetryAttempt(routerUrl, currentAttempt);
      }
    }
  }

  /** Schedules the next retry attempt with exponential backoff. */
  private void scheduleRetryAttempt(URL routerUrl, int currentAttempt) {
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
        () -> attemptGatewayRegistration(routerUrl), delaySeconds, TimeUnit.SECONDS);
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
          LOG.info(String.format("Successfully unregistered router %s from gateway", instanceId));
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
