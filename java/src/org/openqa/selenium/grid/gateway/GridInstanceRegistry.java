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

import java.io.Closeable;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.openqa.selenium.concurrent.ExecutorServices;
import org.openqa.selenium.concurrent.GuardedRunnable;
import org.openqa.selenium.grid.data.DistributorStatus;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.tracing.Tracer;

/**
 * Registry for managing Grid instances in the load balancer. Handles Grid instance discovery,
 * health checking, and load balancing strategies.
 */
public class GridInstanceRegistry implements Closeable {

  private static final Logger LOG = Logger.getLogger(GridInstanceRegistry.class.getName());

  // Load balancing strategy is now handled by LoadBalancingStrategy interface

  private final Tracer tracer;
  private final HttpClient.Factory httpClientFactory;
  private final ConcurrentMap<String, GridInstance> instances;
  private final AtomicInteger roundRobinCounter;
  private final LoadBalancingStrategy loadBalancingStrategy;
  private final Duration healthCheckInterval;
  private final Duration healthCheckTimeout;
  private final int maxFailureCount;
  private ScheduledExecutorService healthCheckExecutor;
  private final Json json;

  public GridInstanceRegistry(
      Tracer tracer,
      HttpClient.Factory httpClientFactory,
      LoadBalancerFactory.StrategyType strategyType,
      Duration healthCheckInterval,
      Duration healthCheckTimeout,
      int maxFailureCount) {
    this(
        tracer,
        httpClientFactory,
        strategyType,
        healthCheckInterval,
        healthCheckTimeout,
        maxFailureCount,
        null);
  }

  public GridInstanceRegistry(
      Tracer tracer,
      HttpClient.Factory httpClientFactory,
      LoadBalancerFactory.StrategyType strategyType,
      Duration healthCheckInterval,
      Duration healthCheckTimeout,
      int maxFailureCount,
      RoutingRulesConfig config) {
    this(
        tracer,
        httpClientFactory,
        strategyType,
        healthCheckInterval,
        healthCheckTimeout,
        maxFailureCount,
        config,
        false);
  }

  public GridInstanceRegistry(
      Tracer tracer,
      HttpClient.Factory httpClientFactory,
      LoadBalancerFactory.StrategyType strategyType,
      Duration healthCheckInterval,
      Duration healthCheckTimeout,
      int maxFailureCount,
      RoutingRulesConfig config,
      boolean forceFallbackOnFinalRetry) {
    this.tracer = Require.nonNull("Tracer", tracer);
    this.httpClientFactory = Require.nonNull("HTTP client factory", httpClientFactory);
    this.instances = new ConcurrentHashMap<>();
    this.roundRobinCounter = new AtomicInteger(0);
    this.healthCheckInterval = Require.nonNull("Health check interval", healthCheckInterval);
    this.healthCheckTimeout = Require.nonNull("Health check timeout", healthCheckTimeout);
    this.maxFailureCount = maxFailureCount;
    this.json = new Json();

    // Create load balancing strategy with optional rule-based overlay and retry-aware fallback
    this.loadBalancingStrategy =
        LoadBalancerFactory.create(
            Require.nonNull("Strategy type", strategyType), config, forceFallbackOnFinalRetry);

    LOG.info("Initialized load balancer: " + loadBalancingStrategy.getStrategyName());

    initializeHealthChecking();
  }

  /**
   * Get the load balancing strategy.
   *
   * @return LoadBalancingStrategy instance
   */
  public LoadBalancingStrategy getLoadBalancingStrategy() {
    return loadBalancingStrategy;
  }

  private void initializeHealthChecking() {
    this.healthCheckExecutor =
        Executors.newScheduledThreadPool(
            2, // Small pool for health checks
            r -> {
              Thread thread = new Thread(r);
              thread.setDaemon(true);
              thread.setName("GridInstanceRegistry - Health Check");
              return thread;
            });

    // Start periodic health checks
    startHealthChecking();
  }

  /** Adds a Grid instance to the registry. */
  public void addGridInstance(String id, URI baseUri) {
    Require.nonNull("Grid instance ID", id);
    Require.nonNull("Grid instance base URI", baseUri);

    GridInstance instance = new GridInstance(id, baseUri);
    instances.put(id, instance);

    LOG.info(String.format("Added Grid instance: %s at %s", id, baseUri));

    // Perform immediate health check for new instance
    performHealthCheck(instance);
  }

  /** Removes a Grid instance from the registry. */
  public void removeGridInstance(String id) {
    GridInstance removed = instances.remove(id);
    if (removed != null) {
      LOG.info(String.format("Removed Grid instance: %s", id));
    }
  }

  /** Gets a Grid instance by ID. */
  public Optional<GridInstance> getGridInstance(String id) {
    return Optional.ofNullable(instances.get(id));
  }

  /** Gets all Grid instances. */
  public Collection<GridInstance> getAllGridInstances() {
    return new ArrayList<>(instances.values());
  }

  /** Gets all healthy Grid instances available for new sessions. */
  public List<GridInstance> getAvailableGridInstances() {
    return instances.values().stream()
        .filter(GridInstance::isAvailableForNewSessions)
        .collect(Collectors.toList());
  }

  /**
   * Gets all healthy Grid instances regardless of capacity for intelligent queuing. This allows
   * Grid instances to handle their own queuing when at capacity.
   */
  public List<GridInstance> getHealthyGridInstances() {
    return instances.values().stream()
        .filter(GridInstance::isHealthy)
        .filter(GridInstance::isEnabled)
        .collect(Collectors.toList());
  }

  /**
   * Selects a Grid instance from healthy instances for intelligent queuing. Falls back to healthy
   * instances when no "available" instances exist.
   */
  public Optional<GridInstance> selectFromHealthyInstances() {
    List<GridInstance> healthy = getHealthyGridInstances();

    if (healthy.isEmpty()) {
      LOG.warning("No healthy Grid instances found for session queuing");
      return Optional.empty();
    }

    // Use the same load balancing strategy but with healthy instances
    Optional<GridInstance> selected = loadBalancingStrategy.selectGridInstance(healthy, null);

    if (selected.isPresent()) {
      LOG.info(
          String.format(
              "Selected healthy Grid instance %s for queuing using %s strategy",
              selected.get().getId(), loadBalancingStrategy.getStrategyName()));
    }

    return selected;
  }

  /** Selects the best Grid instance for a new session based on the load balancing strategy. */
  public Optional<GridInstance> selectGridInstanceForNewSession() {
    return selectGridInstanceForNewSession(null);
  }

  /** Selects the best Grid instance for a new session with capabilities for rule-based routing. */
  public Optional<GridInstance> selectGridInstanceForNewSession(
      org.openqa.selenium.Capabilities requestedCapabilities) {
    List<GridInstance> available = getAvailableGridInstances();

    if (available.isEmpty()) {
      LOG.warning("No available Grid instances for new session");
      return Optional.empty();
    }

    Optional<GridInstance> selected =
        loadBalancingStrategy.selectGridInstance(available, requestedCapabilities);

    if (selected.isPresent()) {
      LOG.fine(
          String.format(
              "Selected Grid instance %s for new session using %s strategy",
              selected.get().getId(), loadBalancingStrategy.getStrategyName()));
    }

    return selected;
  }

  // Load balancing logic moved to individual strategy classes

  /** Updates session count for a Grid instance when a session is created. */
  public void onSessionCreated(String gridInstanceId) {
    GridInstance instance = instances.get(gridInstanceId);
    if (instance != null) {
      instance.incrementSessionCount();
      // Clear pending marker
      clearPendingForInstance(gridInstanceId);
      LOG.fine(
          String.format(
              "Session created on Grid instance %s, new count: %d",
              gridInstanceId, instance.getSessionCount()));
    }
  }

  /** Updates session count for a Grid instance when a session ends. */
  public void onSessionEnded(String gridInstanceId) {
    GridInstance instance = instances.get(gridInstanceId);
    if (instance != null) {
      instance.decrementSessionCount();
      // Clear pending marker when session ends (safety cleanup)
      clearPendingForInstance(gridInstanceId);
      LOG.fine(
          String.format(
              "Session ended on Grid instance %s, new count: %d",
              gridInstanceId, instance.getSessionCount()));
    }
  }

  /** Marks a Grid instance as failed and increments failure count. */
  public void onGridInstanceFailure(String gridInstanceId) {
    GridInstance instance = instances.get(gridInstanceId);
    if (instance != null) {
      instance.incrementFailureCount();
      // Clear pending marker on failure
      clearPendingForInstance(gridInstanceId);

      if (instance.getFailureCount() >= maxFailureCount) {
        instance.setStatus(GridInstance.Status.UNHEALTHY);
        LOG.warning(
            String.format(
                "Grid instance %s marked as unhealthy after %d failures",
                gridInstanceId, instance.getFailureCount()));
      }
    }
  }

  private void startHealthChecking() {
    Runnable healthCheckTask =
        GuardedRunnable.guard(
            () -> {
              for (GridInstance instance : instances.values()) {
                performHealthCheck(instance);
              }
            });

    healthCheckExecutor.scheduleAtFixedRate(
        healthCheckTask,
        0, // Start immediately
        healthCheckInterval.toSeconds(),
        TimeUnit.SECONDS);
  }

  private void performHealthCheck(GridInstance instance) {
    try {
      ClientConfig config =
          ClientConfig.defaultConfig()
              .baseUri(instance.getBaseUri())
              .readTimeout(healthCheckTimeout);

      try (HttpClient client = httpClientFactory.createClient(config)) {
        HttpRequest request = new HttpRequest(HttpMethod.GET, "/se/grid/distributor/status");
        HttpResponse response = client.execute(request);

        if (response.getStatus() == 200) {
          // Parse distributor status to get session count
          try {
            String content;
            try (java.io.Reader reader = Contents.reader(response)) {
              content =
                  new java.io.BufferedReader(reader)
                      .lines()
                      .collect(java.util.stream.Collectors.joining("\n"));
            } catch (Exception e) {
              LOG.warning(
                  "Failed to read response content for "
                      + instance.getId()
                      + ": "
                      + e.getMessage());
              content = "{}";
            }
            DistributorStatus status = json.toType(content, DistributorStatus.class);

            // Update session count from distributor status
            if (status != null) {
              // Calculate total sessions across all nodes
              int totalSessions =
                  status.getNodes().stream()
                      .mapToInt(
                          nodeStatus -> {
                            // Count slots that have active sessions
                            return (int)
                                nodeStatus.getSlots().stream()
                                    .filter(slot -> slot.getSession() != null)
                                    .count();
                          })
                      .sum();
              instance.setSessionCount(totalSessions);
            }
          } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to parse distributor status for " + instance.getId(), e);
          }

          // Mark as healthy
          if (!instance.isHealthy()) {
            instance.setStatus(GridInstance.Status.HEALTHY);
            LOG.info(String.format("Grid instance %s is now healthy", instance.getId()));
          }
          instance.resetFailureCount();

        } else {
          handleHealthCheckFailure(
              instance, String.format("Health check failed with status %d", response.getStatus()));
        }
      }

      instance.updateLastHealthCheck();

    } catch (Exception e) {
      handleHealthCheckFailure(instance, "Health check failed: " + e.getMessage());
    }
  }

  private void handleHealthCheckFailure(GridInstance instance, String reason) {
    instance.incrementFailureCount();

    if (instance.getFailureCount() >= maxFailureCount) {
      if (instance.isHealthy()) {
        instance.setStatus(GridInstance.Status.UNHEALTHY);
        LOG.warning(
            String.format("Grid instance %s marked as unhealthy: %s", instance.getId(), reason));
      }
    } else {
      LOG.fine(
          String.format(
              "Health check failed for %s (%d/%d): %s",
              instance.getId(), instance.getFailureCount(), maxFailureCount, reason));
    }
  }

  /** Clears pending marker for an instance from the load balancing strategy. */
  private void clearPendingForInstance(String gridInstanceId) {
    if (loadBalancingStrategy instanceof RoundRobinLoadBalancer) {
      ((RoundRobinLoadBalancer) loadBalancingStrategy).clearPending(gridInstanceId);
    } else if (loadBalancingStrategy instanceof LeastSessionsLoadBalancer) {
      ((LeastSessionsLoadBalancer) loadBalancingStrategy).clearPending(gridInstanceId);
    }
  }

  @Override
  public void close() {
    ExecutorServices.shutdownGracefully("GridInstanceRegistry - Health Check", healthCheckExecutor);
  }
}
