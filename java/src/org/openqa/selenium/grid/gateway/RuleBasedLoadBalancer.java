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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.logging.Logger;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.internal.Require;

/**
 * Rule-based load balancer that routes sessions based on capabilities matching and weight
 * distribution.
 *
 * <p>Uses YAML-configured routing rules to match session capabilities against predefined criteria
 * and distributes load across Grid instances using weighted selection. Weight calculation is based
 * on maxCapabilities from instance configuration.
 */
public class RuleBasedLoadBalancer implements LoadBalancingStrategy {

  private static final Logger LOG = Logger.getLogger(RuleBasedLoadBalancer.class.getName());

  private final List<RoutingRule> routingRules;
  private final Map<Integer, Integer> instanceCapacities;
  private final Random random;

  public RuleBasedLoadBalancer(
      List<RoutingRule> routingRules, Map<Integer, Integer> instanceCapacities) {
    this.routingRules = Require.nonNull("Routing rules", routingRules);
    this.instanceCapacities = instanceCapacities != null ? instanceCapacities : Map.of();
    this.random = new Random();
  }

  @Override
  public Optional<GridInstance> selectGridInstance(
      List<GridInstance> availableInstances, Capabilities requestedCapabilities) {

    if (availableInstances.isEmpty()) {
      return Optional.empty();
    }

    // Find matching routing rule
    Optional<RoutingRule> matchingRule = findMatchingRule(requestedCapabilities);

    if (matchingRule.isEmpty()) {
      LOG.warning("No matching routing rule found for capabilities: " + requestedCapabilities);
      return Optional.empty();
    }

    // Select instance based on weight distribution
    return selectInstanceByWeight(availableInstances, matchingRule.get());
  }

  private Optional<RoutingRule> findMatchingRule(Capabilities requestedCapabilities) {
    for (RoutingRule rule : routingRules) {
      if (rule.matches(requestedCapabilities)) {
        return Optional.of(rule);
      }
    }
    return Optional.empty();
  }

  private Optional<GridInstance> selectInstanceByWeight(
      List<GridInstance> availableInstances, RoutingRule rule) {

    List<DistributionTarget> targets = rule.getDistribute();

    // Filter targets to only include valid instance indices
    List<DistributionTarget> validTargets =
        targets.stream()
            .filter(target -> target.getIndex() < availableInstances.size())
            .collect(java.util.stream.Collectors.toList());

    if (validTargets.isEmpty()) {
      LOG.warning("No valid distribution targets found for rule");
      return Optional.empty();
    }

    // Calculate effective weights based on maxCapabilities
    List<WeightedTarget> weightedTargets =
        validTargets.stream()
            .map(
                target -> {
                  int instanceIndex = target.getIndex();
                  GridInstance instance = availableInstances.get(instanceIndex);
                  int maxCapacity = instanceCapacities.getOrDefault(instanceIndex, 500);

                  // Weight is based on rule weight * available capacity ratio
                  int availableCapacity = Math.max(0, maxCapacity - instance.getSessionCount());
                  double capacityRatio = availableCapacity / (double) maxCapacity;
                  int effectiveWeight = (int) (target.getWeight() * capacityRatio);

                  return new WeightedTarget(
                      target, instance, Math.max(1, effectiveWeight)); // Minimum weight of 1
                })
            .collect(java.util.stream.Collectors.toList());

    // Calculate total weight
    int totalWeight = weightedTargets.stream().mapToInt(WeightedTarget::getEffectiveWeight).sum();

    if (totalWeight <= 0) {
      // All instances at capacity, use original weights
      totalWeight = validTargets.stream().mapToInt(DistributionTarget::getWeight).sum();
      return selectByOriginalWeight(availableInstances, validTargets, totalWeight);
    }

    // Select instance based on effective weighted random selection
    int randomValue = random.nextInt(totalWeight);
    int currentWeight = 0;

    for (WeightedTarget weightedTarget : weightedTargets) {
      currentWeight += weightedTarget.getEffectiveWeight();
      if (randomValue < currentWeight) {
        LOG.info(
            String.format(
                "Selected Grid instance %d with effective weight %d",
                weightedTarget.getTarget().getIndex(), weightedTarget.getEffectiveWeight()));

        return Optional.of(weightedTarget.getInstance());
      }
    }

    // Fallback to first valid target
    return Optional.of(weightedTargets.get(0).getInstance());
  }

  private Optional<GridInstance> selectByOriginalWeight(
      List<GridInstance> availableInstances,
      List<DistributionTarget> validTargets,
      int totalWeight) {

    int randomValue = random.nextInt(totalWeight);
    int currentWeight = 0;

    for (DistributionTarget target : validTargets) {
      currentWeight += target.getWeight();
      if (randomValue < currentWeight) {
        GridInstance selectedInstance = availableInstances.get(target.getIndex());
        LOG.info(
            String.format(
                "Selected Grid instance %d with original weight %d (all at capacity)",
                target.getIndex(), target.getWeight()));
        return Optional.of(selectedInstance);
      }
    }

    return Optional.of(availableInstances.get(validTargets.get(0).getIndex()));
  }

  private static class WeightedTarget {
    private final DistributionTarget target;
    private final GridInstance instance;
    private final int effectiveWeight;

    public WeightedTarget(DistributionTarget target, GridInstance instance, int effectiveWeight) {
      this.target = target;
      this.instance = instance;
      this.effectiveWeight = effectiveWeight;
    }

    public DistributionTarget getTarget() {
      return target;
    }

    public GridInstance getInstance() {
      return instance;
    }

    public int getEffectiveWeight() {
      return effectiveWeight;
    }
  }

  @Override
  public String getStrategyName() {
    return "RuleBased";
  }
}
