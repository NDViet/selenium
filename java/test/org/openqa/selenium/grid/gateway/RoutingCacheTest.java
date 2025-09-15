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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;

class RoutingCacheTest {

  private GridInstance instance1;
  private GridInstance instance2;
  private GridInstance instance3;
  private List<GridInstance> instances;
  private Capabilities chromeCapabilities;
  private RoutingCache routingCache;

  @BeforeEach
  void setUp() throws Exception {
    instance1 = new GridInstance("grid-1", URI.create("http://grid1:4444"));
    instance2 = new GridInstance("grid-2", URI.create("http://grid2:4444"));
    instance3 = new GridInstance("grid-3", URI.create("http://grid3:4444"));

    instances = Arrays.asList(instance1, instance2, instance3);
    chromeCapabilities = new ImmutableCapabilities("browserName", "chrome");
    routingCache = new RoutingCache(Duration.ofSeconds(5));
  }

  @Test
  void testRoutingCacheBasicOperations() {
    // Initially no pending requests
    assertFalse(routingCache.isPending("grid-1"));
    assertEquals(0, routingCache.size());

    // Mark as pending
    assertTrue(routingCache.markPending("grid-1"));
    assertTrue(routingCache.isPending("grid-1"));
    assertEquals(1, routingCache.size());

    // Cannot mark same instance as pending again
    assertFalse(routingCache.markPending("grid-1"));
    assertEquals(1, routingCache.size());

    // Clear pending
    routingCache.clearPending("grid-1");
    assertFalse(routingCache.isPending("grid-1"));
    assertEquals(0, routingCache.size());
  }

  @Test
  void testRoundRobinWithRoutingCache() {
    RoutingCache sharedCache = new RoutingCache();
    RoundRobinLoadBalancer balancer =
        new RoundRobinLoadBalancer(new CapabilityMatcher(), sharedCache);

    // First request should select an instance and mark it pending
    Optional<GridInstance> first = balancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(first.isPresent());
    assertTrue(sharedCache.isPending(first.get().getId()));

    // Second request should select a different instance
    Optional<GridInstance> second = balancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(second.isPresent());
    assertTrue(sharedCache.isPending(second.get().getId()));
    assertFalse(first.get().getId().equals(second.get().getId()));

    // Third request should select the remaining instance
    Optional<GridInstance> third = balancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(third.isPresent());
    assertTrue(sharedCache.isPending(third.get().getId()));

    // All three instances should be different
    Set<String> selectedIds = new HashSet<>();
    selectedIds.add(first.get().getId());
    selectedIds.add(second.get().getId());
    selectedIds.add(third.get().getId());
    assertEquals(3, selectedIds.size());
  }

  @Test
  void testLeastSessionsWithRoutingCache() {
    // Set different session counts
    instance1.setSessionCount(1);
    instance2.setSessionCount(2);
    instance3.setSessionCount(3);

    RoutingCache sharedCache = new RoutingCache();
    LeastSessionsLoadBalancer balancer =
        new LeastSessionsLoadBalancer(new CapabilityMatcher(), sharedCache);

    // First request should select instance1 (least sessions)
    Optional<GridInstance> first = balancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(first.isPresent());
    assertEquals(instance1, first.get());
    assertTrue(sharedCache.isPending("grid-1"));

    // Second request should select instance2 (next least, since instance1 is pending)
    Optional<GridInstance> second = balancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(second.isPresent());
    assertEquals(instance2, second.get());
    assertTrue(sharedCache.isPending("grid-2"));

    // Third request should select instance3 (only remaining)
    Optional<GridInstance> third = balancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(third.isPresent());
    assertEquals(instance3, third.get());
    assertTrue(sharedCache.isPending("grid-3"));
  }

  @Test
  void testFallbackWhenAllInstancesPending() {
    RoutingCache sharedCache = new RoutingCache();
    RoundRobinLoadBalancer balancer =
        new RoundRobinLoadBalancer(new CapabilityMatcher(), sharedCache);

    // Mark all instances as pending manually
    sharedCache.markPending("grid-1");
    sharedCache.markPending("grid-2");
    sharedCache.markPending("grid-3");

    // Should still select an instance (fallback behavior)
    Optional<GridInstance> selected = balancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(selected.isPresent());
  }

  @Test
  void testClearPendingAfterSessionCreation() {
    RoundRobinLoadBalancer balancer = new RoundRobinLoadBalancer();

    // Select an instance
    Optional<GridInstance> selected = balancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(selected.isPresent());

    // Clear pending marker
    balancer.clearPending(selected.get().getId());

    // Instance should no longer be pending (we can't directly test this without access to the
    // cache,
    // but the next selection should be able to use this instance again)
    Optional<GridInstance> nextSelected =
        balancer.selectGridInstance(instances, chromeCapabilities);
    assertTrue(nextSelected.isPresent());
  }
}
