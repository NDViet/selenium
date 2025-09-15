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
import java.util.concurrent.atomic.AtomicInteger;
import org.openqa.selenium.Capabilities;

/**
 * Round Robin load balancer that distributes requests evenly across available Grid instances.
 * Capability-aware: prioritizes instances with matching slots, falls back to all instances if none
 * match. Routing cache-aware: avoids instances with pending session creation requests.
 */
public class RoundRobinLoadBalancer implements LoadBalancingStrategy {

  private final AtomicInteger counter = new AtomicInteger(0);
  private final CapabilityMatcher capabilityMatcher;
  private final RoutingCache routingCache;

  public RoundRobinLoadBalancer() {
    this.capabilityMatcher = new CapabilityMatcher();
    this.routingCache = new RoutingCache();
  }

  public RoundRobinLoadBalancer(CapabilityMatcher capabilityMatcher) {
    this.capabilityMatcher = capabilityMatcher;
    this.routingCache = new RoutingCache();
  }

  public RoundRobinLoadBalancer(CapabilityMatcher capabilityMatcher, RoutingCache routingCache) {
    this.capabilityMatcher = capabilityMatcher;
    this.routingCache = routingCache;
  }

  @Override
  public Optional<GridInstance> selectGridInstance(
      List<GridInstance> availableInstances, Capabilities requestedCapabilities) {

    if (availableInstances.isEmpty()) {
      return Optional.empty();
    }

    // Try capability-aware selection first
    List<GridInstance> matchingInstances =
        capabilityMatcher.filterByCapabilities(availableInstances, requestedCapabilities);

    // Fallback to all instances if no capability matches
    List<GridInstance> instancesToUse =
        matchingInstances.isEmpty() ? availableInstances : matchingInstances;

    // Filter out instances with pending requests
    List<GridInstance> nonPendingInstances =
        instancesToUse.stream()
            .filter(instance -> !routingCache.isPending(instance.getId()))
            .collect(java.util.stream.Collectors.toList());

    // Use non-pending instances if available, otherwise fallback to all
    List<GridInstance> finalInstances =
        nonPendingInstances.isEmpty() ? instancesToUse : nonPendingInstances;

    int index = counter.getAndIncrement() % finalInstances.size();
    GridInstance selected = finalInstances.get(index);

    // Mark as pending
    routingCache.markPending(selected.getId());

    return Optional.of(selected);
  }

  @Override
  public String getStrategyName() {
    return "RoundRobin";
  }

  /** Clears the pending marker for an instance after session creation completes. */
  public void clearPending(String instanceId) {
    routingCache.clearPending(instanceId);
  }
}
