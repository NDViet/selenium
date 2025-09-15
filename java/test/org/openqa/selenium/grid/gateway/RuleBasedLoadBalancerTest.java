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

class RuleBasedLoadBalancerTest {

  private RuleBasedLoadBalancer loadBalancer;
  private List<GridInstance> gridInstances;

  @BeforeEach
  void setUp() throws Exception {
    // Create test Grid instances
    gridInstances =
        Arrays.asList(
            new GridInstance("grid-0", URI.create("http://grid0:4444")),
            new GridInstance("grid-1", URI.create("http://grid1:4444")),
            new GridInstance("grid-2", URI.create("http://grid2:4444")));

    // Create routing rules matching the YAML example
    List<RoutingRule> rules =
        Arrays.asList(
            // Windows/macOS rule: 70% to index 0, 30% to index 2
            new RoutingRule(
                Map.of("platformName", Arrays.asList("Windows", "macOS")),
                Arrays.asList(new DistributionTarget(0, 70), new DistributionTarget(2, 30))),
            // Linux rule: 100% to index 1
            new RoutingRule(
                Map.of("platformName", Arrays.asList("Linux")),
                Arrays.asList(new DistributionTarget(1, 100))),
            // Safari rule: 100% to index 0
            new RoutingRule(
                Map.of("browserName", Arrays.asList("safari")),
                Arrays.asList(new DistributionTarget(0, 100))),
            // Chrome/Firefox latest rule: 50% to index 1, 50% to index 2
            new RoutingRule(
                Map.of(
                    "browserName", Arrays.asList("chrome", "firefox"),
                    "browserVersion", Arrays.asList("latest")),
                Arrays.asList(new DistributionTarget(1, 50), new DistributionTarget(2, 50))),
            // Default fallback rule: 60% to index 1, 40% to index 2
            new RoutingRule(
                Collections.emptyMap(), // Empty match = default
                Arrays.asList(new DistributionTarget(1, 60), new DistributionTarget(2, 40))));

    loadBalancer = new RuleBasedLoadBalancer(rules, Map.of());
  }

  @Test
  void testWindowsPlatformRouting() {
    Capabilities caps = new ImmutableCapabilities("platformName", "Windows");

    // Test multiple selections to verify weight distribution
    // Should route to index 0 (70%) or index 2 (30%)
    for (int i = 0; i < 10; i++) {
      Optional<GridInstance> selected = loadBalancer.selectGridInstance(gridInstances, caps);
      assertTrue(selected.isPresent());

      String selectedId = selected.get().getId();
      assertTrue(
          selectedId.equals("grid-0") || selectedId.equals("grid-2"),
          "Windows should route to grid-0 or grid-2, got: " + selectedId);
    }
  }

  @Test
  void testMacOSPlatformRouting() {
    Capabilities caps = new ImmutableCapabilities("platformName", "macOS");

    Optional<GridInstance> selected = loadBalancer.selectGridInstance(gridInstances, caps);
    assertTrue(selected.isPresent());

    String selectedId = selected.get().getId();
    assertTrue(
        selectedId.equals("grid-0") || selectedId.equals("grid-2"),
        "macOS should route to grid-0 or grid-2, got: " + selectedId);
  }

  @Test
  void testLinuxPlatformRouting() {
    Capabilities caps = new ImmutableCapabilities("platformName", "Linux");

    Optional<GridInstance> selected = loadBalancer.selectGridInstance(gridInstances, caps);
    assertTrue(selected.isPresent());
    assertEquals("grid-1", selected.get().getId(), "Linux should route to grid-1");
  }

  @Test
  void testSafariBrowserRouting() {
    Capabilities caps = new ImmutableCapabilities("browserName", "safari");

    Optional<GridInstance> selected = loadBalancer.selectGridInstance(gridInstances, caps);
    assertTrue(selected.isPresent());
    assertEquals("grid-0", selected.get().getId(), "Safari should route to grid-0");
  }

  @Test
  void testChromeLatestRouting() {
    Capabilities caps =
        new ImmutableCapabilities(Map.of("browserName", "chrome", "browserVersion", "latest"));

    Optional<GridInstance> selected = loadBalancer.selectGridInstance(gridInstances, caps);
    assertTrue(selected.isPresent());

    String selectedId = selected.get().getId();
    assertTrue(
        selectedId.equals("grid-1") || selectedId.equals("grid-2"),
        "Chrome latest should route to grid-1 or grid-2, got: " + selectedId);
  }

  @Test
  void testFirefoxLatestRouting() {
    Capabilities caps =
        new ImmutableCapabilities(Map.of("browserName", "firefox", "browserVersion", "latest"));

    Optional<GridInstance> selected = loadBalancer.selectGridInstance(gridInstances, caps);
    assertTrue(selected.isPresent());

    String selectedId = selected.get().getId();
    assertTrue(
        selectedId.equals("grid-1") || selectedId.equals("grid-2"),
        "Firefox latest should route to grid-1 or grid-2, got: " + selectedId);
  }

  @Test
  void testDefaultFallbackRouting() {
    // Capabilities that don't match any specific rule
    Capabilities caps = new ImmutableCapabilities("browserName", "edge");

    Optional<GridInstance> selected = loadBalancer.selectGridInstance(gridInstances, caps);
    assertTrue(selected.isPresent());

    String selectedId = selected.get().getId();
    assertTrue(
        selectedId.equals("grid-1") || selectedId.equals("grid-2"),
        "Default fallback should route to grid-1 or grid-2, got: " + selectedId);
  }

  @Test
  void testEmptyInstanceList() {
    Capabilities caps = new ImmutableCapabilities("platformName", "Windows");

    Optional<GridInstance> selected =
        loadBalancer.selectGridInstance(Collections.emptyList(), caps);
    assertFalse(selected.isPresent(), "Should return empty when no instances available");
  }

  @Test
  void testOutOfBoundsIndex() {
    // Create rule with index beyond available instances
    List<RoutingRule> rules =
        Arrays.asList(
            new RoutingRule(
                Map.of("platformName", Arrays.asList("Windows")),
                Arrays.asList(new DistributionTarget(5, 100)) // Index 5 doesn't exist
                ));

    RuleBasedLoadBalancer testBalancer = new RuleBasedLoadBalancer(rules, Map.of());
    Capabilities caps = new ImmutableCapabilities("platformName", "Windows");

    Optional<GridInstance> selected = testBalancer.selectGridInstance(gridInstances, caps);
    assertFalse(selected.isPresent(), "Should return empty when all indices are out of bounds");
  }

  @Test
  void testMixedValidAndInvalidIndices() {
    // Create rule with mix of valid and invalid indices
    List<RoutingRule> rules =
        Arrays.asList(
            new RoutingRule(
                Map.of("platformName", Arrays.asList("Windows")),
                Arrays.asList(
                    new DistributionTarget(0, 50), // Valid
                    new DistributionTarget(5, 50) // Invalid
                    )));

    RuleBasedLoadBalancer testBalancer = new RuleBasedLoadBalancer(rules, Map.of());
    Capabilities caps = new ImmutableCapabilities("platformName", "Windows");

    Optional<GridInstance> selected = testBalancer.selectGridInstance(gridInstances, caps);
    assertTrue(selected.isPresent());
    assertEquals(
        "grid-0", selected.get().getId(), "Should select valid index when mixed with invalid");
  }

  @Test
  void testZeroWeight() {
    List<RoutingRule> rules =
        Arrays.asList(
            new RoutingRule(
                Map.of("platformName", Arrays.asList("Windows")),
                Arrays.asList(new DistributionTarget(0, 0)) // Zero weight
                ));

    RuleBasedLoadBalancer testBalancer = new RuleBasedLoadBalancer(rules, Map.of());
    Capabilities caps = new ImmutableCapabilities("platformName", "Windows");

    Optional<GridInstance> selected = testBalancer.selectGridInstance(gridInstances, caps);
    assertFalse(selected.isPresent(), "Should return empty when total weight is zero");
  }

  @Test
  void testMultipleCapabilitiesMatch() {
    // Test that all match criteria must be satisfied
    Capabilities caps =
        new ImmutableCapabilities(
            Map.of(
                "browserName", "chrome",
                "browserVersion", "latest",
                "platformName", "Linux" // This should NOT match the chrome+latest rule
                ));

    Optional<GridInstance> selected = loadBalancer.selectGridInstance(gridInstances, caps);
    assertTrue(selected.isPresent());

    // Should match Linux rule (100% to grid-1) instead of chrome+latest rule
    assertEquals(
        "grid-1", selected.get().getId(), "Should match Linux rule, not chrome+latest rule");
  }

  @Test
  void testRuleOrderPrecedence() {
    // Safari on macOS should match Safari rule (first match wins)
    Capabilities caps =
        new ImmutableCapabilities(Map.of("browserName", "safari", "platformName", "macOS"));

    Optional<GridInstance> selected = loadBalancer.selectGridInstance(gridInstances, caps);
    assertTrue(selected.isPresent());
    assertEquals(
        "grid-0", selected.get().getId(), "Should match Safari rule (grid-0), not macOS rule");
  }

  @Test
  void testStrategyName() {
    assertEquals("RuleBased", loadBalancer.getStrategyName());
  }
}
