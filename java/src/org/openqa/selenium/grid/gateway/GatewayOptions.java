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

import static org.openqa.selenium.grid.config.StandardGridRoles.GATEWAY_ROLE;

import com.beust.jcommander.Parameter;
import com.google.auto.service.AutoService;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.ConfigValue;
import org.openqa.selenium.grid.config.HasRoles;
import org.openqa.selenium.grid.config.Role;

/** Configuration options for the Gateway. */
@SuppressWarnings("FieldMayBeFinal")
@AutoService(HasRoles.class)
public class GatewayOptions implements HasRoles {

  private static final Logger LOG = Logger.getLogger(GatewayOptions.class.getName());

  private final Config config;

  public GatewayOptions() {
    this.config = null;
  }

  public GatewayOptions(Config config) {
    this.config = config;
  }

  @Parameter(
      names = {"--routing-rules-file"},
      description = "Path to YAML file containing routing rules configuration")
  @ConfigValue(
      section = "gateway",
      name = "routing-rules-file",
      example = "\"/path/to/routing-rules.yaml\"")
  private String routingRulesFile = null;

  @Parameter(
      names = {"--enable-routing-rules"},
      description = "Enable YAML-based routing rules for session distribution")
  @ConfigValue(section = "gateway", name = "enable-routing-rules", example = "true")
  private boolean enableRoutingRules = true;

  @Parameter(
      names = {"--enable-discovery-endpoints"},
      description = "Enable discovery endpoints (POST/DELETE /discovery)")
  @ConfigValue(section = "gateway", name = "enable-discovery-endpoints", example = "true")
  private boolean enableDiscoveryEndpoints = true;

  @Parameter(
      names = {"--enable-individual-status"},
      description = "Enable individual Grid instance status endpoints (/status/{instanceId})")
  @ConfigValue(section = "gateway", name = "enable-individual-status", example = "true")
  private boolean enableIndividualStatus = true;

  @Parameter(
      names = {"--routing-rules-validation"},
      description = "Validate routing rules against available Grid instances on startup")
  @ConfigValue(section = "gateway", name = "routing-rules-validation", example = "true")
  private boolean routingRulesValidation = true;

  @Parameter(
      names = {"--fallback-to-load-balancer"},
      description = "Fall back to default load balancing when routing rules fail")
  @ConfigValue(section = "gateway", name = "fallback-to-load-balancer", example = "true")
  private boolean fallbackToLoadBalancer = true;

  @Parameter(
      names = {"--routing-rules-cache-ttl"},
      description = "Time-to-live for routing rules cache in seconds")
  @ConfigValue(section = "gateway", name = "routing-rules-cache-ttl", example = "300")
  private int routingRulesCacheTtlSeconds = 300;

  @Parameter(
      names = {"--discovery-registration-timeout"},
      description = "Timeout for discovery registration operations in seconds")
  @ConfigValue(section = "gateway", name = "discovery-registration-timeout", example = "30")
  private int discoveryRegistrationTimeoutSeconds = 30;

  @Parameter(
      names = {"--gateway-metrics-enabled"},
      description = "Enable Gateway-specific metrics collection")
  @ConfigValue(section = "gateway", name = "gateway-metrics-enabled", example = "true")
  private boolean gatewayMetricsEnabled = true;

  @Parameter(
      names = {"--grid-instance"},
      description = "Grid instance URL to register on startup (can be specified multiple times)")
  @ConfigValue(
      section = "gateway",
      name = "grid-instances",
      example = "[\"http://grid1:4444\", \"http://grid2:4444\", \"http://grid3:4444\"]")
  private List<String> gridInstances = new ArrayList<>();

  @Parameter(
      names = {"--sub-path"},
      description = "Sub-path for the Gateway (e.g., /selenium)")
  @ConfigValue(section = "gateway", name = "sub-path", example = "\"/selenium\"")
  private String subPath = null;

  @Parameter(
      names = "--username",
      description =
          "User name clients must use to connect to the server. "
              + "Both this and password need to be set in order to be used.")
  @ConfigValue(section = "gateway", name = "username", example = "admin")
  private String username;

  @Parameter(
      names = "--password",
      description =
          "Password clients must use to connect to the server. "
              + "Both this and the username need to be set in order to be used.")
  @ConfigValue(section = "gateway", name = "password", example = "hunter2")
  private String password;

  @Parameter(
      names = {"--load-balancing-strategy"},
      description = "Load balancing strategy: ROUND_ROBIN, GREEDY, or LEAST_SESSIONS")
  @ConfigValue(section = "gateway", name = "load-balancing-strategy", example = "\"GREEDY\"")
  private String loadBalancingStrategy = "GREEDY";

  @Parameter(
      names = {"--max-retry-attempts"},
      description = "Maximum number of retry attempts when creating a new session")
  @ConfigValue(section = "gateway", name = "max-retry-attempts", example = "3")
  private int maxRetryAttempts = 3;

  @Parameter(
      names = {"--force-fallback-on-final-retry"},
      description =
          "Force scheduling to another instance on final retry (may break rule-based routing)")
  @ConfigValue(section = "gateway", name = "force-fallback-on-final-retry", example = "true")
  private boolean forceFallbackOnFinalRetry = true;

  @Parameter(
      names = {"--request-timeout"},
      description = "Timeout for HTTP requests to Grid instances (in seconds)")
  @ConfigValue(section = "gateway", name = "request-timeout", example = "60")
  private int requestTimeoutSeconds = 60;

  @Parameter(
      names = {"--health-check-interval"},
      description = "Interval between health checks for Grid instances (in seconds)")
  @ConfigValue(section = "gateway", name = "health-check-interval", example = "30")
  private int healthCheckIntervalSeconds = 30;

  @Parameter(
      names = {"--health-check-timeout"},
      description = "Timeout for health check requests (in seconds)")
  @ConfigValue(section = "gateway", name = "health-check-timeout", example = "10")
  private int healthCheckTimeoutSeconds = 10;

  @Parameter(
      names = {"--connection-timeout"},
      description = "Timeout for establishing connections to Grid instances (in seconds)")
  @ConfigValue(section = "gateway", name = "connection-timeout", example = "30")
  private int connectionTimeoutSeconds = 30;

  @Parameter(
      names = {"--max-failure-count"},
      description =
          "Maximum number of consecutive failures before marking a Grid instance as unhealthy")
  @ConfigValue(section = "gateway", name = "max-failure-count", example = "3")
  private int maxFailureCount = 3;

  @Parameter(
      names = {"--session-cleanup-interval"},
      description = "Interval for cleaning up stale session mappings (in seconds)")
  @ConfigValue(section = "gateway", name = "session-cleanup-interval", example = "300")
  private int sessionCleanupIntervalSeconds = 300;

  @Parameter(
      names = {"--enable-metrics"},
      description = "Enable metrics collection for the load balancer")
  @ConfigValue(section = "gateway", name = "enable-metrics", example = "true")
  private boolean enableMetrics = true;

  @Parameter(
      names = {"--discovery-enabled"},
      description = "Enable dynamic Grid instance discovery")
  @ConfigValue(section = "gateway", name = "discovery-enabled", example = "true")
  private boolean discoveryEnabled = false;

  @Parameter(
      names = {"--discovery-interval"},
      description = "Interval for discovering new Grid instances (in seconds)")
  @ConfigValue(section = "gateway", name = "discovery-interval", example = "15")
  private int discoveryIntervalSeconds = 15;

  @Parameter(
      names = {"--service-registry-type"},
      description = "Type of service registry: HTTP, CONSUL, ETCD, KUBERNETES")
  @ConfigValue(section = "gateway", name = "service-registry-type", example = "\"HTTP\"")
  private String serviceRegistryType = "HTTP";

  @Parameter(
      names = {"--service-registry-timeout"},
      description = "Timeout for service registry operations (in seconds)")
  @ConfigValue(section = "gateway", name = "service-registry-timeout", example = "120")
  private int serviceRegistryTimeoutSeconds = 120;

  @Override
  public Set<Role> getRoles() {
    return Set.of(GATEWAY_ROLE);
  }

  /**
   * Get the path to the routing rules YAML file.
   *
   * @return the routing rules file path, or null if not configured
   */
  public String getRoutingRulesFile() {
    return config == null
        ? routingRulesFile
        : config.get("gateway", "routing-rules-file").orElse(routingRulesFile);
  }

  /**
   * Check if routing rules are enabled.
   *
   * @return true if routing rules are enabled
   */
  public boolean isRoutingRulesEnabled() {
    return config == null
        ? enableRoutingRules
        : config.getBool("gateway", "enable-routing-rules").orElse(enableRoutingRules);
  }

  /**
   * Check if discovery endpoints are enabled.
   *
   * @return true if discovery endpoints are enabled
   */
  public boolean isDiscoveryEndpointsEnabled() {
    return config == null
        ? enableDiscoveryEndpoints
        : config.getBool("gateway", "enable-discovery-endpoints").orElse(enableDiscoveryEndpoints);
  }

  /**
   * Check if individual status endpoints are enabled.
   *
   * @return true if individual status endpoints are enabled
   */
  public boolean isIndividualStatusEnabled() {
    return config == null
        ? enableIndividualStatus
        : config.getBool("gateway", "enable-individual-status").orElse(enableIndividualStatus);
  }

  /**
   * Check if routing rules validation is enabled.
   *
   * @return true if routing rules validation is enabled
   */
  public boolean isRoutingRulesValidationEnabled() {
    return config == null
        ? routingRulesValidation
        : config.getBool("gateway", "routing-rules-validation").orElse(routingRulesValidation);
  }

  /**
   * Check if fallback to load balancer is enabled.
   *
   * @return true if fallback to load balancer is enabled
   */
  public boolean isFallbackToLoadBalancerEnabled() {
    return config == null
        ? fallbackToLoadBalancer
        : config.getBool("gateway", "fallback-to-load-balancer").orElse(fallbackToLoadBalancer);
  }

  /**
   * Get the routing rules cache TTL in seconds.
   *
   * @return the cache TTL in seconds
   */
  public int getRoutingRulesCacheTtlSeconds() {
    return config == null
        ? routingRulesCacheTtlSeconds
        : config.getInt("gateway", "routing-rules-cache-ttl").orElse(routingRulesCacheTtlSeconds);
  }

  /**
   * Get the discovery registration timeout in seconds.
   *
   * @return the timeout in seconds
   */
  public int getDiscoveryRegistrationTimeoutSeconds() {
    return config == null
        ? discoveryRegistrationTimeoutSeconds
        : config
            .getInt("gateway", "discovery-registration-timeout")
            .orElse(discoveryRegistrationTimeoutSeconds);
  }

  /**
   * Check if Gateway-specific metrics are enabled.
   *
   * @return true if Gateway metrics are enabled
   */
  public boolean isGatewayMetricsEnabled() {
    return config == null
        ? gatewayMetricsEnabled
        : config.getBool("gateway", "gateway-metrics-enabled").orElse(gatewayMetricsEnabled);
  }

  /**
   * Get the predefined Grid instance URLs as a list.
   *
   * @return list of Grid instance URLs, empty if not configured
   */
  public List<String> getGridInstances() {
    if (config == null) {
      return gridInstances;
    }

    // Try to get as list first (TOML array)
    try {
      return config.getAll("gateway", "grid-instances").orElse(gridInstances);
    } catch (Exception e) {
      // Fallback to comma-separated string for backward compatibility
      String instancesStr = config.get("gateway", "grid-instances").orElse("");
      if (instancesStr.isEmpty()) {
        return gridInstances;
      }
      return java.util.Arrays.stream(instancesStr.split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .collect(java.util.stream.Collectors.toList());
    }
  }

  /**
   * Get the sub-path for the Gateway.
   *
   * @return the sub-path, normalized with leading slash and no trailing slash
   */
  public String getSubPath() {
    String path = config == null ? subPath : config.get("gateway", "sub-path").orElse(subPath);

    if (path == null || path.trim().isEmpty()) {
      return "";
    }

    path = path.trim();
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  /**
   * Get the username for basic authentication.
   *
   * @return the username, or null if not configured
   */
  public String getUsername() {
    return config == null ? username : config.get("gateway", "username").orElse(username);
  }

  /**
   * Get the password for basic authentication.
   *
   * @return the password, or null if not configured
   */
  public String getPassword() {
    return config == null ? password : config.get("gateway", "password").orElse(password);
  }

  /**
   * Check if UI is disabled.
   *
   * @return true if UI is disabled
   */
  public boolean isDisableUi() {
    return config == null ? false : config.getBool("gateway", "disable-ui").orElse(false);
  }

  public LoadBalancerFactory.StrategyType getLoadBalancingStrategy() {
    String strategy =
        config == null
            ? loadBalancingStrategy
            : config.get("gateway", "load-balancing-strategy").orElse(loadBalancingStrategy);
    try {
      return LoadBalancerFactory.StrategyType.valueOf(strategy.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Invalid load balancing strategy: "
              + strategy
              + ". Valid options are: ROUND_ROBIN, GREEDY, LEAST_SESSIONS",
          e);
    }
  }

  public int getMaxRetryAttempts() {
    return config == null
        ? maxRetryAttempts
        : config.getInt("gateway", "max-retry-attempts").orElse(maxRetryAttempts);
  }

  public boolean isForceFallbackOnFinalRetry() {
    return config == null
        ? forceFallbackOnFinalRetry
        : config
            .getBool("gateway", "force-fallback-on-final-retry")
            .orElse(forceFallbackOnFinalRetry);
  }

  public Duration getRequestTimeout() {
    int timeout =
        config == null
            ? requestTimeoutSeconds
            : config.getInt("gateway", "request-timeout").orElse(requestTimeoutSeconds);
    return Duration.ofSeconds(timeout);
  }

  public Duration getHealthCheckInterval() {
    int interval =
        config == null
            ? healthCheckIntervalSeconds
            : config.getInt("gateway", "health-check-interval").orElse(healthCheckIntervalSeconds);
    return Duration.ofSeconds(interval);
  }

  public Duration getHealthCheckTimeout() {
    int timeout =
        config == null
            ? healthCheckTimeoutSeconds
            : config.getInt("gateway", "health-check-timeout").orElse(healthCheckTimeoutSeconds);
    return Duration.ofSeconds(timeout);
  }

  public Duration getConnectionTimeout() {
    int timeout =
        config == null
            ? connectionTimeoutSeconds
            : config.getInt("gateway", "connection-timeout").orElse(connectionTimeoutSeconds);
    return Duration.ofSeconds(timeout);
  }

  public int getMaxFailureCount() {
    return config == null
        ? maxFailureCount
        : config.getInt("gateway", "max-failure-count").orElse(maxFailureCount);
  }

  public Duration getSessionCleanupInterval() {
    int interval =
        config == null
            ? sessionCleanupIntervalSeconds
            : config
                .getInt("gateway", "session-cleanup-interval")
                .orElse(sessionCleanupIntervalSeconds);
    return Duration.ofSeconds(interval);
  }

  public boolean isEnableMetrics() {
    return config == null
        ? enableMetrics
        : config.getBool("gateway", "enable-metrics").orElse(enableMetrics);
  }

  public boolean isDiscoveryEnabled() {
    return config == null
        ? discoveryEnabled
        : config.getBool("gateway", "discovery-enabled").orElse(discoveryEnabled);
  }

  public Duration getDiscoveryInterval() {
    int interval =
        config == null
            ? discoveryIntervalSeconds
            : config.getInt("gateway", "discovery-interval").orElse(discoveryIntervalSeconds);
    return Duration.ofSeconds(interval);
  }

  public String getServiceRegistryType() {
    return config == null
        ? serviceRegistryType
        : config.get("gateway", "service-registry-type").orElse(serviceRegistryType);
  }

  public Duration getServiceRegistryTimeout() {
    int timeout =
        config == null
            ? serviceRegistryTimeoutSeconds
            : config
                .getInt("gateway", "service-registry-timeout")
                .orElse(serviceRegistryTimeoutSeconds);
    return Duration.ofSeconds(timeout);
  }

  public List<URI> getGridInstanceUris() {
    List<String> instances = getGridInstances();
    List<URI> uris = new ArrayList<>();
    for (String uriStr : instances) {
      try {
        uris.add(URI.create(uriStr.trim()));
      } catch (Exception e) {
        throw new IllegalArgumentException("Invalid Grid instance URI: " + uriStr, e);
      }
    }
    return uris;
  }

  /**
   * Load routing rules configuration from the configured YAML file.
   *
   * @return RoutingRulesConfig instance, or null if no file is configured or routing rules are
   *     disabled
   */
  public RoutingRulesConfig loadRoutingRulesConfig() {
    if (!isRoutingRulesEnabled()) {
      LOG.info("Routing rules are disabled");
      return null;
    }

    String filePath = getRoutingRulesFile();
    if (filePath == null || filePath.trim().isEmpty()) {
      LOG.info("No routing rules file configured");
      return null;
    }

    try {
      Path yamlPath = Paths.get(filePath);
      RoutingRulesConfig config = RoutingRulesConfig.fromYamlFile(yamlPath);
      LOG.info("Loaded routing rules from: " + filePath);
      return config;
    } catch (IOException e) {
      LOG.severe("Failed to load routing rules from file: " + filePath + " - " + e.getMessage());
      if (isFallbackToLoadBalancerEnabled()) {
        LOG.info("Falling back to default load balancing due to routing rules loading failure");
        return null;
      } else {
        throw new RuntimeException("Failed to load routing rules and fallback is disabled", e);
      }
    } catch (Exception e) {
      LOG.severe("Failed to parse routing rules from file: " + filePath + " - " + e.getMessage());
      if (isFallbackToLoadBalancerEnabled()) {
        LOG.info("Falling back to default load balancing due to routing rules parsing failure");
        return null;
      } else {
        throw new RuntimeException("Failed to parse routing rules and fallback is disabled", e);
      }
    }
  }

  @Override
  public String toString() {
    return "GatewayOptions{"
        + "routingRulesFile='"
        + getRoutingRulesFile()
        + '\''
        + ", enableRoutingRules="
        + isRoutingRulesEnabled()
        + ", enableDiscoveryEndpoints="
        + isDiscoveryEndpointsEnabled()
        + ", enableIndividualStatus="
        + isIndividualStatusEnabled()
        + ", routingRulesValidation="
        + isRoutingRulesValidationEnabled()
        + ", fallbackToLoadBalancer="
        + isFallbackToLoadBalancerEnabled()
        + ", routingRulesCacheTtlSeconds="
        + getRoutingRulesCacheTtlSeconds()
        + ", discoveryRegistrationTimeoutSeconds="
        + getDiscoveryRegistrationTimeoutSeconds()
        + ", gatewayMetricsEnabled="
        + isGatewayMetricsEnabled()
        + ", gridInstances="
        + getGridInstances()
        + ", subPath='"
        + getSubPath()
        + '\''
        + '}';
  }
}
