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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;

class RetryAwareLoadBalancerTest {

  private RetryAwareLoadBalancer retryAwareLoadBalancer;
  private List<GridInstance> gridInstances;
  private MockLoadBalancingStrategy mockStrategy;

  @BeforeEach
  void setUp() throws Exception {
    gridInstances =
        Arrays.asList(
            new GridInstance("grid-0", URI.create("http://grid0:4444")),
            new GridInstance("grid-1", URI.create("http://grid1:4444")),
            new GridInstance("grid-2", URI.create("http://grid2:4444")));

    mockStrategy = new MockLoadBalancingStrategy();
    retryAwareLoadBalancer = new RetryAwareLoadBalancer(mockStrategy, true);
  }

  @Test
  void testNormalSelection() {
    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");

    Optional<GridInstance> selected =
        retryAwareLoadBalancer.selectGridInstance(gridInstances, caps);

    assertTrue(selected.isPresent());
    assertEquals("grid-0", selected.get().getId()); // Mock strategy returns first instance
  }

  @Test
  void testForcedFallbackOnFinalRetry() {
    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");
    GridInstance failedInstance = gridInstances.get(0); // grid-0

    // Simulate final retry (retry count > 0)
    retryAwareLoadBalancer.setRetryCount(2); // Final retry
    retryAwareLoadBalancer.setPreviousFailedInstance(failedInstance);

    Optional<GridInstance> selected =
        retryAwareLoadBalancer.selectGridInstance(gridInstances, caps);

    assertTrue(selected.isPresent());
    assertNotEquals("grid-0", selected.get().getId()); // Should avoid failed instance
    assertEquals("grid-1", selected.get().getId()); // Should select first alternative
  }

  @Test
  void testNoFallbackOnEarlyRetry() {
    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");
    GridInstance failedInstance = gridInstances.get(0);

    // Simulate early retry (retry count = 0)
    retryAwareLoadBalancer.setRetryCount(0);
    retryAwareLoadBalancer.setPreviousFailedInstance(failedInstance);

    Optional<GridInstance> selected =
        retryAwareLoadBalancer.selectGridInstance(gridInstances, caps);

    assertTrue(selected.isPresent());
    assertEquals("grid-0", selected.get().getId()); // Should use normal strategy
  }

  @Test
  void testFallbackWithNoAlternatives() {
    List<GridInstance> singleInstance = Arrays.asList(gridInstances.get(0));
    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");

    // Simulate final retry with only one instance (the failed one)
    retryAwareLoadBalancer.setRetryCount(2);
    retryAwareLoadBalancer.setPreviousFailedInstance(gridInstances.get(0));

    Optional<GridInstance> selected =
        retryAwareLoadBalancer.selectGridInstance(singleInstance, caps);

    assertTrue(selected.isPresent());
    assertEquals("grid-0", selected.get().getId()); // Should fallback to normal strategy
  }

  @Test
  void testStrategyName() {
    assertEquals("Mock+RetryAware", retryAwareLoadBalancer.getStrategyName());
  }

  @Test
  void testClearRetryContext() {
    retryAwareLoadBalancer.setRetryCount(2);
    retryAwareLoadBalancer.setPreviousFailedInstance(gridInstances.get(0));

    retryAwareLoadBalancer.clearRetryContext();

    // After clearing, should behave like normal selection
    Optional<GridInstance> selected =
        retryAwareLoadBalancer.selectGridInstance(gridInstances, null);
    assertTrue(selected.isPresent());
    assertEquals("grid-0", selected.get().getId());
  }

  @Test
  void testDisabledForceFallback() {
    RetryAwareLoadBalancer disabledFallback = new RetryAwareLoadBalancer(mockStrategy, false);
    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");

    // Even on final retry, should not force fallback when disabled
    disabledFallback.setRetryCount(2);
    disabledFallback.setPreviousFailedInstance(gridInstances.get(0));

    Optional<GridInstance> selected = disabledFallback.selectGridInstance(gridInstances, caps);

    assertTrue(selected.isPresent());
    assertEquals("grid-0", selected.get().getId()); // Should use normal strategy
  }

  // Mock strategy that always returns the first available instance
  private static class MockLoadBalancingStrategy implements LoadBalancingStrategy {
    @Override
    public Optional<GridInstance> selectGridInstance(
        List<GridInstance> availableInstances, Capabilities requestedCapabilities) {
      return availableInstances.isEmpty()
          ? Optional.empty()
          : Optional.of(availableInstances.get(0));
    }

    @Override
    public String getStrategyName() {
      return "Mock";
    }
  }
}
