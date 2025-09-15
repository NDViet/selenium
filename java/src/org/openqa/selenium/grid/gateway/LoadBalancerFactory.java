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

import java.util.Map;
import java.util.stream.Collectors;

/** Factory for creating load balancing strategies based on configuration. */
public class LoadBalancerFactory {

  public enum StrategyType {
    ROUND_ROBIN,
    GREEDY,
    LEAST_SESSIONS
  }

  /** Create a load balancer based on strategy type and configuration. */
  public static LoadBalancingStrategy create(StrategyType strategyType, RoutingRulesConfig config) {
    return create(strategyType, config, false);
  }

  /** Create a load balancer with retry-aware fallback option. */
  public static LoadBalancingStrategy create(
      StrategyType strategyType, RoutingRulesConfig config, boolean forceFallbackOnFinalRetry) {
    // Create base strategy
    LoadBalancingStrategy baseStrategy = createBaseStrategy(strategyType, config);

    // Add rule-based overlay if routing rules are configured
    LoadBalancingStrategy strategy = baseStrategy;
    if (config != null && config.hasRoutingRules()) {
      RuleBasedLoadBalancer ruleBasedStrategy = config.createRuleBasedLoadBalancer();
      strategy = new CompositeLoadBalancer(baseStrategy, ruleBasedStrategy);
    }

    // Wrap with retry-aware logic if enabled
    if (forceFallbackOnFinalRetry) {
      strategy = new RetryAwareLoadBalancer(strategy, true);
    }

    return strategy;
  }

  private static LoadBalancingStrategy createBaseStrategy(
      StrategyType strategyType, RoutingRulesConfig config) {
    switch (strategyType) {
      case ROUND_ROBIN:
        return new RoundRobinLoadBalancer();

      case GREEDY:
        Map<Integer, Integer> capacities = extractInstanceCapacities(config);
        return new GreedyLoadBalancer(capacities);

      case LEAST_SESSIONS:
        return new LeastSessionsLoadBalancer();

      default:
        throw new IllegalArgumentException("Unknown strategy type: " + strategyType);
    }
  }

  private static Map<Integer, Integer> extractInstanceCapacities(RoutingRulesConfig config) {
    if (config == null || !config.hasInstances()) {
      return Map.of();
    }

    return config.getInstances().stream()
        .collect(Collectors.toMap(InstanceConfig::getIndex, InstanceConfig::getMaxCapabilities));
  }
}
