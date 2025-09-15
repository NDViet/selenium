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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * Configuration parser for YAML-based routing rules. Loads and parses routing rules from YAML
 * configuration files.
 */
public class RoutingRulesConfig {

  private static final Logger LOG = Logger.getLogger(RoutingRulesConfig.class.getName());

  private final List<RoutingRule> routingRules;
  private final List<InstanceConfig> instances;
  private final boolean enabled;

  public RoutingRulesConfig(List<RoutingRule> routingRules, List<InstanceConfig> instances) {
    this(routingRules, instances, true);
  }

  public RoutingRulesConfig(
      List<RoutingRule> routingRules, List<InstanceConfig> instances, boolean enabled) {
    this.routingRules = routingRules != null ? routingRules : Collections.emptyList();
    this.instances = instances != null ? instances : Collections.emptyList();
    this.enabled = enabled;
  }

  public RoutingRulesConfig(List<RoutingRule> routingRules) {
    this(routingRules, Collections.emptyList(), true);
  }

  /**
   * Load routing rules from a YAML file.
   *
   * @param yamlFilePath Path to the YAML configuration file
   * @return RoutingRulesConfig instance with loaded rules
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if the YAML format is invalid
   */
  public static RoutingRulesConfig fromYamlFile(Path yamlFilePath) throws IOException {
    if (!Files.exists(yamlFilePath)) {
      throw new IOException("YAML file does not exist: " + yamlFilePath);
    }

    try (InputStream inputStream = new FileInputStream(yamlFilePath.toFile())) {
      return fromYamlStream(inputStream);
    }
  }

  /**
   * Load routing rules from a YAML input stream.
   *
   * @param inputStream InputStream containing YAML configuration
   * @return RoutingRulesConfig instance with loaded rules
   * @throws IllegalArgumentException if the YAML format is invalid
   */
  public static RoutingRulesConfig fromYamlStream(InputStream inputStream) {
    try {
      Yaml yaml = new Yaml();
      Map<String, Object> config = yaml.load(inputStream);
      return config != null ? parseConfig(config) : new RoutingRulesConfig(Collections.emptyList());
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Failed to parse YAML configuration: " + e.getMessage(), e);
    }
  }

  /**
   * Load routing rules from a YAML string.
   *
   * @param yamlContent String containing YAML configuration
   * @return RoutingRulesConfig instance with loaded rules
   * @throws IllegalArgumentException if the YAML format is invalid
   */
  public static RoutingRulesConfig fromYamlString(String yamlContent) {
    if (yamlContent == null || yamlContent.trim().isEmpty()) {
      return new RoutingRulesConfig(Collections.emptyList());
    }

    try {
      Yaml yaml = new Yaml();
      Map<String, Object> config = yaml.load(yamlContent);
      return config != null ? parseConfig(config) : new RoutingRulesConfig(Collections.emptyList());
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Failed to parse YAML configuration: " + e.getMessage(), e);
    }
  }

  @SuppressWarnings("unchecked")
  private static RoutingRulesConfig parseConfig(Map<String, Object> config) {
    List<RoutingRule> rules = new ArrayList<>();
    List<InstanceConfig> instances = new ArrayList<>();

    // Parse global enabled field (default to true if not specified)
    boolean enabled = true;
    Object enabledObj = config.get("enabled");
    if (enabledObj instanceof Boolean) {
      enabled = (Boolean) enabledObj;
    }

    // Parse routing rules
    Object routingRulesObj = config.get("routingRules");
    if (routingRulesObj != null) {
      if (!(routingRulesObj instanceof List)) {
        throw new IllegalArgumentException("'routingRules' must be a list");
      }

      List<Map<String, Object>> routingRulesList = (List<Map<String, Object>>) routingRulesObj;

      for (int i = 0; i < routingRulesList.size(); i++) {
        Map<String, Object> ruleMap = routingRulesList.get(i);
        try {
          RoutingRule rule = parseRoutingRule(ruleMap);
          rules.add(rule);
          LOG.fine("Parsed routing rule " + (i + 1) + ": " + rule);
        } catch (Exception e) {
          throw new IllegalArgumentException("Failed to parse routing rule " + (i + 1), e);
        }
      }
    }

    // Parse instances configuration
    Object instancesObj = config.get("instances");
    if (instancesObj != null) {
      if (!(instancesObj instanceof List)) {
        throw new IllegalArgumentException("'instances' must be a list");
      }

      List<Map<String, Object>> instancesList = (List<Map<String, Object>>) instancesObj;

      for (int i = 0; i < instancesList.size(); i++) {
        Map<String, Object> instanceMap = instancesList.get(i);
        try {
          InstanceConfig instance = parseInstanceConfig(instanceMap);
          instances.add(instance);
          LOG.fine("Parsed instance config " + (i + 1) + ": " + instance);
        } catch (Exception e) {
          throw new IllegalArgumentException("Failed to parse instance config " + (i + 1), e);
        }
      }
    }

    LOG.info(
        "Loaded "
            + rules.size()
            + " routing rules and "
            + instances.size()
            + " instance configs from YAML configuration (enabled: "
            + enabled
            + ")");
    return new RoutingRulesConfig(rules, instances, enabled);
  }

  @SuppressWarnings("unchecked")
  private static RoutingRule parseRoutingRule(Map<String, Object> ruleMap) {
    // Parse match criteria - support both 'match' and 'matchCriteria' formats
    Map<String, Object> matchMap = (Map<String, Object>) ruleMap.get("match");
    if (matchMap == null) {
      matchMap = (Map<String, Object>) ruleMap.get("matchCriteria");
    }
    Map<String, List<String>> matchCriteria = parseMatchCriteria(matchMap);

    // Parse distribute rules
    List<Map<String, Object>> distributeList =
        (List<Map<String, Object>>) ruleMap.get("distribute");
    if (distributeList == null || distributeList.isEmpty()) {
      throw new IllegalArgumentException("'distribute' section is required and cannot be empty");
    }

    List<DistributionTarget> distributeTargets = new ArrayList<>();
    for (Map<String, Object> distributeMap : distributeList) {
      DistributionTarget target = parseDistributionTarget(distributeMap);
      distributeTargets.add(target);
    }

    // Parse enabled field (default to true if not specified)
    boolean enabled = true;
    Object enabledObj = ruleMap.get("enabled");
    if (enabledObj instanceof Boolean) {
      enabled = (Boolean) enabledObj;
    }

    return new RoutingRule(matchCriteria, distributeTargets, enabled);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> parseMatchCriteria(Map<String, Object> matchMap) {
    if (matchMap == null) {
      return Collections.emptyMap();
    }

    Map<String, List<String>> criteria = new java.util.HashMap<>();

    for (Map.Entry<String, Object> entry : matchMap.entrySet()) {
      String key = entry.getKey();
      List<String> values = parseStringList(entry.getValue());
      if (values != null && !values.isEmpty()) {
        criteria.put(key, values);
      }
    }

    return criteria;
  }

  @SuppressWarnings("unchecked")
  private static List<String> parseStringList(Object value) {
    if (value == null) {
      return null;
    }

    if (value instanceof String) {
      return List.of((String) value);
    }

    if (value instanceof List) {
      List<Object> list = (List<Object>) value;
      return list.stream().map(Object::toString).collect(java.util.stream.Collectors.toList());
    }

    throw new IllegalArgumentException(
        "Expected string or list of strings, got: " + value.getClass());
  }

  private static DistributionTarget parseDistributionTarget(Map<String, Object> distributeMap) {
    // Support both 'index' and 'instanceIndex' formats
    Object indexObj = distributeMap.get("index");
    if (indexObj == null) {
      indexObj = distributeMap.get("instanceIndex");
    }
    Object weightObj = distributeMap.get("weight");

    if (indexObj == null) {
      throw new IllegalArgumentException(
          "'index' or 'instanceIndex' is required in distribute rule");
    }
    if (weightObj == null) {
      throw new IllegalArgumentException("'weight' is required in distribute rule");
    }

    int index;
    int weight;

    try {
      index = ((Number) indexObj).intValue();
    } catch (ClassCastException e) {
      throw new IllegalArgumentException("'index' must be a number, got: " + indexObj);
    }

    try {
      weight = ((Number) weightObj).intValue();
    } catch (ClassCastException e) {
      throw new IllegalArgumentException("'weight' must be a number, got: " + weightObj);
    }

    return new DistributionTarget(index, weight);
  }

  private static InstanceConfig parseInstanceConfig(Map<String, Object> instanceMap) {
    Object indexObj = instanceMap.get("index");
    Object maxCapabilitiesObj = instanceMap.get("maxCapabilities");

    if (indexObj == null) {
      throw new IllegalArgumentException("'index' is required in instance config");
    }
    if (maxCapabilitiesObj == null) {
      throw new IllegalArgumentException("'maxCapabilities' is required in instance config");
    }

    int index;
    int maxCapabilities;

    try {
      index = ((Number) indexObj).intValue();
    } catch (ClassCastException e) {
      throw new IllegalArgumentException("'index' must be a number, got: " + indexObj);
    }

    try {
      maxCapabilities = ((Number) maxCapabilitiesObj).intValue();
    } catch (ClassCastException e) {
      throw new IllegalArgumentException(
          "'maxCapabilities' must be a number, got: " + maxCapabilitiesObj);
    }

    return new InstanceConfig(index, maxCapabilities);
  }

  /**
   * Get the loaded routing rules.
   *
   * @return list of routing rules
   */
  public List<RoutingRule> getRoutingRules() {
    return routingRules;
  }

  /**
   * Get the loaded instance configurations.
   *
   * @return list of instance configurations
   */
  public List<InstanceConfig> getInstances() {
    return instances;
  }

  /**
   * Check if any routing rules are configured.
   *
   * @return true if routing rules exist
   */
  public boolean hasRoutingRules() {
    return !routingRules.isEmpty();
  }

  /**
   * Check if routing rules are enabled.
   *
   * @return true if routing rules are enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Check if routing rules are active (both exist and enabled).
   *
   * @return true if routing rules exist and are enabled
   */
  public boolean isActive() {
    return hasRoutingRules() && enabled;
  }

  /**
   * Check if any instance configurations are provided.
   *
   * @return true if instance configurations exist
   */
  public boolean hasInstances() {
    return !instances.isEmpty();
  }

  /**
   * Create a rule-based load balancer from this configuration.
   *
   * @return RuleBasedLoadBalancer instance
   */
  public RuleBasedLoadBalancer createRuleBasedLoadBalancer() {
    Map<Integer, Integer> capacities =
        instances.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    InstanceConfig::getIndex, InstanceConfig::getMaxCapabilities));
    return new RuleBasedLoadBalancer(routingRules, capacities);
  }

  /**
   * Convert the current routing rules configuration to YAML format.
   *
   * @return YAML string representation of the routing rules
   */
  public String toYamlString() {
    StringBuilder yaml = new StringBuilder();

    // Add global enabled field
    yaml.append("enabled: ").append(enabled).append("\n");

    if (hasRoutingRules()) {
      yaml.append("routingRules:\n");
      for (RoutingRule rule : routingRules) {
        yaml.append("  - enabled: ").append(rule.isEnabled()).append("\n");
        yaml.append("    match:\n");
        for (Map.Entry<String, List<String>> entry : rule.getMatch().entrySet()) {
          yaml.append("      ").append(entry.getKey()).append(":\n");
          for (String value : entry.getValue()) {
            yaml.append("        - ").append(value).append("\n");
          }
        }
        yaml.append("    distribute:\n");
        for (DistributionTarget target : rule.getDistribute()) {
          yaml.append("      - index: ").append(target.getIndex()).append("\n");
          yaml.append("        weight: ").append(target.getWeight()).append("\n");
        }
      }
    }

    if (hasInstances()) {
      yaml.append("instances:\n");
      for (InstanceConfig instance : instances) {
        yaml.append("  - index: ").append(instance.getIndex()).append("\n");
        yaml.append("    maxCapabilities: ").append(instance.getMaxCapabilities()).append("\n");
      }
    }

    return yaml.toString();
  }

  @Override
  public String toString() {
    return "RoutingRulesConfig{" + "routingRules=" + routingRules.size() + " rules" + '}';
  }
}
