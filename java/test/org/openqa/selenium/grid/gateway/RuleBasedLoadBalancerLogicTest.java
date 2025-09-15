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

class RuleBasedLoadBalancerLogicTest {

  private List<GridInstance> gridInstances;

  @BeforeEach
  void setUp() throws Exception {
    gridInstances =
        Arrays.asList(
            new GridInstance("grid-0", URI.create("http://grid0:4444")),
            new GridInstance("grid-1", URI.create("http://grid1:4444")),
            new GridInstance("grid-2", URI.create("http://grid2:4444")));
  }

  @Test
  void testAndLogicForMatchCriteria() {
    // Rule requires BOTH browserName=chrome AND platformName=Windows
    List<RoutingRule> rules =
        Arrays.asList(
            new RoutingRule(
                Map.of(
                    "browserName", Arrays.asList("chrome"),
                    "platformName", Arrays.asList("Windows")),
                Arrays.asList(new DistributionTarget(0, 100))));

    RuleBasedLoadBalancer loadBalancer = new RuleBasedLoadBalancer(rules, Map.of());

    // Should match - both criteria satisfied
    Capabilities matchingCaps =
        new ImmutableCapabilities(Map.of("browserName", "chrome", "platformName", "Windows"));
    Optional<GridInstance> result = loadBalancer.selectGridInstance(gridInstances, matchingCaps);
    assertTrue(result.isPresent());
    assertEquals("grid-0", result.get().getId());

    // Should NOT match - only browserName satisfied
    Capabilities partialMatch1 = new ImmutableCapabilities("browserName", "chrome");
    result = loadBalancer.selectGridInstance(gridInstances, partialMatch1);
    assertFalse(result.isPresent());

    // Should NOT match - only platformName satisfied
    Capabilities partialMatch2 = new ImmutableCapabilities("platformName", "Windows");
    result = loadBalancer.selectGridInstance(gridInstances, partialMatch2);
    assertFalse(result.isPresent());

    // Should NOT match - wrong values
    Capabilities noMatch =
        new ImmutableCapabilities(Map.of("browserName", "firefox", "platformName", "Linux"));
    result = loadBalancer.selectGridInstance(gridInstances, noMatch);
    assertFalse(result.isPresent());
  }

  @Test
  void testRuleEvaluationOrder_FirstMatchWins() {
    // Rule 1: Specific - chrome + Windows → grid-0
    // Rule 2: General - chrome → grid-1
    List<RoutingRule> rules =
        Arrays.asList(
            new RoutingRule(
                Map.of(
                    "browserName", Arrays.asList("chrome"),
                    "platformName", Arrays.asList("Windows")),
                Arrays.asList(new DistributionTarget(0, 100))),
            new RoutingRule(
                Map.of("browserName", Arrays.asList("chrome")),
                Arrays.asList(new DistributionTarget(1, 100))));

    RuleBasedLoadBalancer loadBalancer = new RuleBasedLoadBalancer(rules, Map.of());

    // Should match first rule (more specific)
    Capabilities caps =
        new ImmutableCapabilities(Map.of("browserName", "chrome", "platformName", "Windows"));
    Optional<GridInstance> result = loadBalancer.selectGridInstance(gridInstances, caps);
    assertTrue(result.isPresent());
    assertEquals("grid-0", result.get().getId(), "Should match first rule, not second");

    // Should match second rule (first doesn't match)
    Capabilities caps2 = new ImmutableCapabilities("browserName", "chrome");
    result = loadBalancer.selectGridInstance(gridInstances, caps2);
    assertTrue(result.isPresent());
    assertEquals("grid-1", result.get().getId(), "Should match second rule");
  }

  @Test
  void testRuleEvaluationOrder_ReversedRules() {
    // Rule 1: General - chrome → grid-1
    // Rule 2: Specific - chrome + Windows → grid-0
    List<RoutingRule> rules =
        Arrays.asList(
            new RoutingRule(
                Map.of("browserName", Arrays.asList("chrome")),
                Arrays.asList(new DistributionTarget(1, 100))),
            new RoutingRule(
                Map.of(
                    "browserName", Arrays.asList("chrome"),
                    "platformName", Arrays.asList("Windows")),
                Arrays.asList(new DistributionTarget(0, 100))));

    RuleBasedLoadBalancer loadBalancer = new RuleBasedLoadBalancer(rules, Map.of());

    // Should match first rule (general), even though second is more specific
    Capabilities caps =
        new ImmutableCapabilities(Map.of("browserName", "chrome", "platformName", "Windows"));
    Optional<GridInstance> result = loadBalancer.selectGridInstance(gridInstances, caps);
    assertTrue(result.isPresent());
    assertEquals("grid-1", result.get().getId(), "Should match first rule due to order");
  }

  @Test
  void testNoRulesMatch_ReturnsEmpty() {
    List<RoutingRule> rules =
        Arrays.asList(
            new RoutingRule(
                Map.of("browserName", Arrays.asList("chrome")),
                Arrays.asList(new DistributionTarget(0, 100))),
            new RoutingRule(
                Map.of("platformName", Arrays.asList("Windows")),
                Arrays.asList(new DistributionTarget(1, 100))));

    RuleBasedLoadBalancer loadBalancer = new RuleBasedLoadBalancer(rules, Map.of());

    // Capabilities that don't match any rule
    Capabilities caps = new ImmutableCapabilities("browserName", "safari");
    Optional<GridInstance> result = loadBalancer.selectGridInstance(gridInstances, caps);
    assertFalse(result.isPresent(), "Should return empty when no rules match");
  }

  @Test
  void testEmptyRulesList_ReturnsEmpty() {
    RuleBasedLoadBalancer loadBalancer =
        new RuleBasedLoadBalancer(Collections.emptyList(), Map.of());

    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");
    Optional<GridInstance> result = loadBalancer.selectGridInstance(gridInstances, caps);
    assertFalse(result.isPresent(), "Should return empty when no rules configured");
  }

  @Test
  void testComplexAndLogic_ThreeCriteria() {
    // Rule requires ALL three criteria
    List<RoutingRule> rules =
        Arrays.asList(
            new RoutingRule(
                Map.of(
                    "browserName", Arrays.asList("chrome"),
                    "platformName", Arrays.asList("Windows"),
                    "browserVersion", Arrays.asList("120.0")),
                Arrays.asList(new DistributionTarget(0, 100))));

    RuleBasedLoadBalancer loadBalancer = new RuleBasedLoadBalancer(rules, Map.of());

    // Should match - all three criteria satisfied
    Capabilities allMatch =
        new ImmutableCapabilities(
            Map.of(
                "browserName", "chrome",
                "platformName", "Windows",
                "browserVersion", "120.0"));
    Optional<GridInstance> result = loadBalancer.selectGridInstance(gridInstances, allMatch);
    assertTrue(result.isPresent());
    assertEquals("grid-0", result.get().getId());

    // Should NOT match - missing browserVersion
    Capabilities twoMatch =
        new ImmutableCapabilities(Map.of("browserName", "chrome", "platformName", "Windows"));
    result = loadBalancer.selectGridInstance(gridInstances, twoMatch);
    assertFalse(result.isPresent());
  }

  @Test
  void testArrayValuesOrLogic() {
    // Rule matches chrome OR firefox
    List<RoutingRule> rules =
        Arrays.asList(
            new RoutingRule(
                Map.of("browserName", Arrays.asList("chrome", "firefox")),
                Arrays.asList(new DistributionTarget(0, 100))));

    RuleBasedLoadBalancer loadBalancer = new RuleBasedLoadBalancer(rules, Map.of());

    // Should match chrome
    Capabilities chromeCaps = new ImmutableCapabilities("browserName", "chrome");
    Optional<GridInstance> result = loadBalancer.selectGridInstance(gridInstances, chromeCaps);
    assertTrue(result.isPresent());
    assertEquals("grid-0", result.get().getId());

    // Should match firefox
    Capabilities firefoxCaps = new ImmutableCapabilities("browserName", "firefox");
    result = loadBalancer.selectGridInstance(gridInstances, firefoxCaps);
    assertTrue(result.isPresent());
    assertEquals("grid-0", result.get().getId());

    // Should NOT match safari
    Capabilities safariCaps = new ImmutableCapabilities("browserName", "safari");
    result = loadBalancer.selectGridInstance(gridInstances, safariCaps);
    assertFalse(result.isPresent());
  }

  @Test
  void testMixedAndOrLogic() {
    // Rule: (chrome OR firefox) AND Windows
    List<RoutingRule> rules =
        Arrays.asList(
            new RoutingRule(
                Map.of(
                    "browserName", Arrays.asList("chrome", "firefox"),
                    "platformName", Arrays.asList("Windows")),
                Arrays.asList(new DistributionTarget(0, 100))));

    RuleBasedLoadBalancer loadBalancer = new RuleBasedLoadBalancer(rules, Map.of());

    // Should match: chrome + Windows
    Capabilities chromeWindows =
        new ImmutableCapabilities(Map.of("browserName", "chrome", "platformName", "Windows"));
    Optional<GridInstance> result = loadBalancer.selectGridInstance(gridInstances, chromeWindows);
    assertTrue(result.isPresent());

    // Should match: firefox + Windows
    Capabilities firefoxWindows =
        new ImmutableCapabilities(Map.of("browserName", "firefox", "platformName", "Windows"));
    result = loadBalancer.selectGridInstance(gridInstances, firefoxWindows);
    assertTrue(result.isPresent());

    // Should NOT match: chrome + Linux
    Capabilities chromeLinux =
        new ImmutableCapabilities(Map.of("browserName", "chrome", "platformName", "Linux"));
    result = loadBalancer.selectGridInstance(gridInstances, chromeLinux);
    assertFalse(result.isPresent());

    // Should NOT match: safari + Windows
    Capabilities safariWindows =
        new ImmutableCapabilities(Map.of("browserName", "safari", "platformName", "Windows"));
    result = loadBalancer.selectGridInstance(gridInstances, safariWindows);
    assertFalse(result.isPresent());
  }
}
