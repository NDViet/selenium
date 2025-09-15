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
import java.util.Optional;
import java.util.logging.Logger;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.internal.Require;

/**
 * Composite load balancer that combines RuleBased routing with a fallback strategy. If routing
 * rules are configured and match, uses RuleBased selection. Otherwise, falls back to the configured
 * base strategy.
 */
public class CompositeLoadBalancer implements LoadBalancingStrategy {

  private static final Logger LOG = Logger.getLogger(CompositeLoadBalancer.class.getName());

  private final LoadBalancingStrategy baseStrategy;
  private final RuleBasedLoadBalancer ruleBasedStrategy;

  public CompositeLoadBalancer(
      LoadBalancingStrategy baseStrategy, RuleBasedLoadBalancer ruleBasedStrategy) {
    this.baseStrategy = Require.nonNull("Base strategy", baseStrategy);
    this.ruleBasedStrategy = ruleBasedStrategy; // Can be null if no rules configured
  }

  @Override
  public Optional<GridInstance> selectGridInstance(
      List<GridInstance> availableInstances, Capabilities requestedCapabilities) {

    if (availableInstances.isEmpty()) {
      return Optional.empty();
    }

    // Try rule-based selection first if rules are configured and capabilities provided
    if (ruleBasedStrategy != null && requestedCapabilities != null) {
      Optional<GridInstance> ruleBasedResult =
          ruleBasedStrategy.selectGridInstance(availableInstances, requestedCapabilities);

      if (ruleBasedResult.isPresent()) {
        LOG.fine("Selected instance using RuleBased strategy: " + ruleBasedResult.get().getId());
        return ruleBasedResult;
      }
    }

    // Fallback to base strategy
    Optional<GridInstance> baseResult =
        baseStrategy.selectGridInstance(availableInstances, requestedCapabilities);
    if (baseResult.isPresent()) {
      LOG.fine(
          "Selected instance using "
              + baseStrategy.getStrategyName()
              + " strategy: "
              + baseResult.get().getId());
    }

    return baseResult;
  }

  @Override
  public String getStrategyName() {
    if (ruleBasedStrategy != null) {
      return baseStrategy.getStrategyName() + "+RuleBased";
    }
    return baseStrategy.getStrategyName();
  }

  public LoadBalancingStrategy getBaseStrategy() {
    return baseStrategy;
  }

  public RuleBasedLoadBalancer getRuleBasedStrategy() {
    return ruleBasedStrategy;
  }
}
