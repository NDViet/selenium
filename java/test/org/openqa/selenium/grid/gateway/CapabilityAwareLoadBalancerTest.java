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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;

class CapabilityAwareLoadBalancerTest {

  private GridInstance instance1;
  private GridInstance instance2;
  private GridInstance instance3;
  private List<GridInstance> instances;
  private Capabilities chromeCapabilities;

  @BeforeEach
  void setUp() throws Exception {
    instance1 = new GridInstance("grid-1", URI.create("http://grid1:4444"));
    instance2 = new GridInstance("grid-2", URI.create("http://grid2:4444"));
    instance3 = new GridInstance("grid-3", URI.create("http://grid3:4444"));

    // Set different session counts for testing
    instance1.setSessionCount(5);
    instance2.setSessionCount(2);
    instance3.setSessionCount(8);

    instances = Arrays.asList(instance1, instance2, instance3);
    chromeCapabilities = new ImmutableCapabilities("browserName", "chrome");
  }

  @Test
  void testRoundRobinWithCapabilityAwareness() {
    RoundRobinLoadBalancer balancer = new RoundRobinLoadBalancer();

    // Test multiple selections to verify round robin behavior
    Optional<GridInstance> first = balancer.selectGridInstance(instances, chromeCapabilities);
    Optional<GridInstance> second = balancer.selectGridInstance(instances, chromeCapabilities);
    Optional<GridInstance> third = balancer.selectGridInstance(instances, chromeCapabilities);

    assertTrue(first.isPresent());
    assertTrue(second.isPresent());
    assertTrue(third.isPresent());

    // With routing cache, each request should select a different instance
    // Clear pending markers to test fourth request
    balancer.clearPending(first.get().getId());
    balancer.clearPending(second.get().getId());
    balancer.clearPending(third.get().getId());

    Optional<GridInstance> fourth = balancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(fourth.isPresent());
  }

  @Test
  void testLeastSessionsWithCapabilityAwareness() {
    LeastSessionsLoadBalancer balancer = new LeastSessionsLoadBalancer();

    Optional<GridInstance> selected = balancer.selectGridInstance(instances, chromeCapabilities);

    assertTrue(selected.isPresent());
    // Should select instance2 which has the least sessions (2)
    assertEquals(instance2, selected.get());
  }

  @Test
  void testEmptyInstancesList() {
    RoundRobinLoadBalancer roundRobinBalancer = new RoundRobinLoadBalancer();
    LeastSessionsLoadBalancer leastSessionsBalancer = new LeastSessionsLoadBalancer();

    Optional<GridInstance> roundRobinResult =
        roundRobinBalancer.selectGridInstance(Arrays.asList(), chromeCapabilities);
    Optional<GridInstance> leastSessionsResult =
        leastSessionsBalancer.selectGridInstance(Arrays.asList(), chromeCapabilities);

    assertTrue(roundRobinResult.isEmpty());
    assertTrue(leastSessionsResult.isEmpty());
  }

  @Test
  void testNullCapabilities() {
    RoundRobinLoadBalancer roundRobinBalancer = new RoundRobinLoadBalancer();
    LeastSessionsLoadBalancer leastSessionsBalancer = new LeastSessionsLoadBalancer();

    Optional<GridInstance> roundRobinResult =
        roundRobinBalancer.selectGridInstance(instances, null);
    Optional<GridInstance> leastSessionsResult =
        leastSessionsBalancer.selectGridInstance(instances, null);

    assertTrue(roundRobinResult.isPresent());
    assertTrue(leastSessionsResult.isPresent());

    // Should still work with null capabilities (fallback behavior)
    assertEquals(instance1, roundRobinResult.get());
    assertEquals(instance2, leastSessionsResult.get()); // Least sessions
  }

  @Test
  void testStrategyNames() {
    RoundRobinLoadBalancer roundRobinBalancer = new RoundRobinLoadBalancer();
    LeastSessionsLoadBalancer leastSessionsBalancer = new LeastSessionsLoadBalancer();

    assertEquals("RoundRobin", roundRobinBalancer.getStrategyName());
    assertEquals("LeastSessions", leastSessionsBalancer.getStrategyName());
  }

  @Test
  void testCapabilityMatchingWithMockStatus() {
    // Mock capability matcher that validates JSON parsing by simulating instance 2 having chrome
    // slots
    CapabilityMatcher mockMatcher =
        new CapabilityMatcher() {
          @Override
          public List<GridInstance> filterByCapabilities(
              List<GridInstance> instances, Capabilities requestedCapabilities) {
            // Simulate parsing the example JSON status response:
            // Instance 2 has chrome slot available with stereotype {"browserName": "chrome",
            // "browserVersion": "140.0"}
            if (requestedCapabilities != null
                && "chrome".equals(requestedCapabilities.getBrowserName())) {
              return instances.stream()
                  .filter(instance -> "grid-2".equals(instance.getId()))
                  .collect(java.util.stream.Collectors.toList());
            }
            return instances;
          }
        };

    RoundRobinLoadBalancer roundRobinBalancer = new RoundRobinLoadBalancer(mockMatcher);
    LeastSessionsLoadBalancer leastSessionsBalancer = new LeastSessionsLoadBalancer(mockMatcher);

    // Test with chrome capabilities - should select instance 2 which has chrome slots
    Optional<GridInstance> roundRobinResult =
        roundRobinBalancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(roundRobinResult.isPresent());
    assertEquals(instance2, roundRobinResult.get());

    Optional<GridInstance> leastSessionsResult =
        leastSessionsBalancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(leastSessionsResult.isPresent());
    assertEquals(instance2, leastSessionsResult.get());
  }
}
