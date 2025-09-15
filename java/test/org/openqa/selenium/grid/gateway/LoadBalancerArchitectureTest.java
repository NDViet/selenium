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

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;

class LoadBalancerArchitectureTest {

  private List<GridInstance> gridInstances;
  private RoutingRulesConfig configWithRules;
  private RoutingRulesConfig configWithoutRules;

  @BeforeEach
  void setUp() throws Exception {
    // Create test Grid instances with different session counts
    GridInstance instance0 = new GridInstance("grid-0", URI.create("http://grid0:4444"));
    instance0.setSessionCount(50);

    GridInstance instance1 = new GridInstance("grid-1", URI.create("http://grid1:4444"));
    instance1.setSessionCount(100);

    GridInstance instance2 = new GridInstance("grid-2", URI.create("http://grid2:4444"));
    instance2.setSessionCount(25);

    gridInstances = Arrays.asList(instance0, instance1, instance2);

    // Config with routing rules and instance capacities
    List<RoutingRule> rules =
        Arrays.asList(
            new RoutingRule(
                Map.of("browserName", Arrays.asList("chrome")),
                Arrays.asList(new DistributionTarget(0, 70), new DistributionTarget(1, 30))));

    List<InstanceConfig> instances =
        Arrays.asList(
            new InstanceConfig(0, 200), new InstanceConfig(1, 300), new InstanceConfig(2, 150));

    configWithRules = new RoutingRulesConfig(rules, instances);
    configWithoutRules = new RoutingRulesConfig(Collections.emptyList(), instances);
  }

  @Test
  void testRoundRobinStrategy() {
    LoadBalancingStrategy strategy =
        LoadBalancerFactory.create(
            LoadBalancerFactory.StrategyType.ROUND_ROBIN, configWithoutRules);

    assertEquals("RoundRobin", strategy.getStrategyName());

    // Test round robin distribution
    Optional<GridInstance> first = strategy.selectGridInstance(gridInstances, null);
    Optional<GridInstance> second = strategy.selectGridInstance(gridInstances, null);
    Optional<GridInstance> third = strategy.selectGridInstance(gridInstances, null);
    Optional<GridInstance> fourth = strategy.selectGridInstance(gridInstances, null);

    assertTrue(first.isPresent());
    assertTrue(second.isPresent());
    assertTrue(third.isPresent());
    assertTrue(fourth.isPresent());

    // Should cycle through instances
    assertEquals("grid-0", first.get().getId());
    assertEquals("grid-1", second.get().getId());
    assertEquals("grid-2", third.get().getId());
    assertEquals("grid-0", fourth.get().getId()); // Back to first
  }

  @Test
  void testLeastSessionsStrategy() {
    LoadBalancingStrategy strategy =
        LoadBalancerFactory.create(
            LoadBalancerFactory.StrategyType.LEAST_SESSIONS, configWithoutRules);

    assertEquals("LeastSessions", strategy.getStrategyName());

    // Should select instance with fewest sessions (grid-2 with 25 sessions)
    Optional<GridInstance> selected = strategy.selectGridInstance(gridInstances, null);
    assertTrue(selected.isPresent());
    assertEquals("grid-2", selected.get().getId());
  }

  @Test
  void testGreedyStrategy() {
    LoadBalancingStrategy strategy =
        LoadBalancerFactory.create(LoadBalancerFactory.StrategyType.GREEDY, configWithRules);

    assertEquals("Greedy", strategy.getStrategyName());

    // Should select instance with highest available capacity
    // grid-0: 200 - 50 = 150 available
    // grid-1: 300 - 100 = 200 available (highest)
    // grid-2: 150 - 25 = 125 available
    Optional<GridInstance> selected = strategy.selectGridInstance(gridInstances, null);
    assertTrue(selected.isPresent());
    assertEquals("grid-1", selected.get().getId());
  }

  @Test
  void testCompositeWithRules_RuleMatches() {
    LoadBalancingStrategy strategy =
        LoadBalancerFactory.create(LoadBalancerFactory.StrategyType.GREEDY, configWithRules);

    assertEquals("Greedy+RuleBased", strategy.getStrategyName());
    assertTrue(strategy instanceof CompositeLoadBalancer);

    // Chrome request should match rule and use RuleBased selection
    Capabilities chromeCaps = new ImmutableCapabilities("browserName", "chrome");
    Optional<GridInstance> selected = strategy.selectGridInstance(gridInstances, chromeCaps);

    assertTrue(selected.isPresent());
    // Should select grid-0 or grid-1 based on rule weights (70/30)
    String selectedId = selected.get().getId();
    assertTrue(selectedId.equals("grid-0") || selectedId.equals("grid-1"));
  }

  @Test
  void testCompositeWithRules_NoRuleMatch_FallbackToBase() {
    LoadBalancingStrategy strategy =
        LoadBalancerFactory.create(LoadBalancerFactory.StrategyType.GREEDY, configWithRules);

    // Firefox request doesn't match any rule, should fallback to Greedy
    Capabilities firefoxCaps = new ImmutableCapabilities("browserName", "firefox");
    Optional<GridInstance> selected = strategy.selectGridInstance(gridInstances, firefoxCaps);

    assertTrue(selected.isPresent());
    // Should use Greedy strategy (highest available capacity = grid-1)
    assertEquals("grid-1", selected.get().getId());
  }

  @Test
  void testCompositeWithRules_NullCapabilities_FallbackToBase() {
    LoadBalancingStrategy strategy =
        LoadBalancerFactory.create(
            LoadBalancerFactory.StrategyType.LEAST_SESSIONS, configWithRules);

    // Null capabilities should fallback to base strategy
    Optional<GridInstance> selected = strategy.selectGridInstance(gridInstances, null);

    assertTrue(selected.isPresent());
    // Should use LeastSessions strategy (grid-2 with 25 sessions)
    assertEquals("grid-2", selected.get().getId());
  }

  @Test
  void testStrategyWithoutRules_NoComposite() {
    LoadBalancingStrategy strategy =
        LoadBalancerFactory.create(
            LoadBalancerFactory.StrategyType.ROUND_ROBIN, configWithoutRules);

    assertEquals("RoundRobin", strategy.getStrategyName());
    assertFalse(strategy instanceof CompositeLoadBalancer);
  }

  @Test
  void testWeightCalculationBasedOnCapacity() {
    // Create instances at different capacity levels
    GridInstance nearCapacity = new GridInstance("grid-0", URI.create("http://grid0:4444"));
    nearCapacity.setSessionCount(190); // 190/200 = 95% capacity

    GridInstance lowCapacity = new GridInstance("grid-1", URI.create("http://grid1:4444"));
    lowCapacity.setSessionCount(50); // 50/300 = 17% capacity

    List<GridInstance> instances = Arrays.asList(nearCapacity, lowCapacity);

    LoadBalancingStrategy strategy =
        LoadBalancerFactory.create(LoadBalancerFactory.StrategyType.GREEDY, configWithRules);

    Capabilities chromeCaps = new ImmutableCapabilities("browserName", "chrome");

    // Test multiple selections to verify weight distribution favors low capacity instance
    int grid0Count = 0;
    int grid1Count = 0;

    for (int i = 0; i < 100; i++) {
      Optional<GridInstance> selected = strategy.selectGridInstance(instances, chromeCaps);
      assertTrue(selected.isPresent());

      if ("grid-0".equals(selected.get().getId())) {
        grid0Count++;
      } else if ("grid-1".equals(selected.get().getId())) {
        grid1Count++;
      }
    }

    // grid-1 should be selected more often due to lower capacity usage
    assertTrue(
        grid1Count > grid0Count,
        String.format(
            "Expected grid-1 (%d) to be selected more than grid-0 (%d)", grid1Count, grid0Count));
  }

  @Test
  void testAllInstancesExceedCapacity_ContinuesOperating() {
    // Create instances that all exceed their configured capacity
    GridInstance overCapacity0 = new GridInstance("grid-0", URI.create("http://grid0:4444"));
    overCapacity0.setSessionCount(250); // Exceeds 200 capacity

    GridInstance overCapacity1 = new GridInstance("grid-1", URI.create("http://grid1:4444"));
    overCapacity1.setSessionCount(350); // Exceeds 300 capacity

    List<GridInstance> instances = Arrays.asList(overCapacity0, overCapacity1);

    LoadBalancingStrategy strategy =
        LoadBalancerFactory.create(LoadBalancerFactory.StrategyType.GREEDY, configWithRules);

    Capabilities chromeCaps = new ImmutableCapabilities("browserName", "chrome");
    Optional<GridInstance> selected = strategy.selectGridInstance(instances, chromeCaps);

    // Should still select an instance even when all exceed capacity
    assertTrue(selected.isPresent());
    String selectedId = selected.get().getId();
    assertTrue(selectedId.equals("grid-0") || selectedId.equals("grid-1"));
  }

  @Test
  void testEmptyInstancesList() {
    LoadBalancingStrategy strategy =
        LoadBalancerFactory.create(LoadBalancerFactory.StrategyType.GREEDY, configWithRules);

    Optional<GridInstance> selected = strategy.selectGridInstance(Collections.emptyList(), null);
    assertFalse(selected.isPresent());
  }
}
