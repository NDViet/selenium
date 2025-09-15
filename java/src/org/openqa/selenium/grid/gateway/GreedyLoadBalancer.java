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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.openqa.selenium.Capabilities;

/**
 * Greedy load balancer that routes to instances with highest available capacity. Uses
 * maxCapabilities as threshold - continues operating even when all instances exceed capacity.
 */
public class GreedyLoadBalancer implements LoadBalancingStrategy {

  private final Map<Integer, Integer> instanceCapacities;

  public GreedyLoadBalancer(Map<Integer, Integer> instanceCapacities) {
    this.instanceCapacities = instanceCapacities != null ? instanceCapacities : Map.of();
  }

  @Override
  public Optional<GridInstance> selectGridInstance(
      List<GridInstance> availableInstances, Capabilities requestedCapabilities) {

    if (availableInstances.isEmpty()) {
      return Optional.empty();
    }

    // Select instance with highest available capacity
    return availableInstances.stream()
        .max(Comparator.comparingInt(this::getAvailableCapacity))
        .or(() -> Optional.of(availableInstances.get(0))); // Fallback to first instance
  }

  private int getAvailableCapacity(GridInstance instance) {
    int instanceIndex = getInstanceIndex(instance);
    int maxCapacity = instanceCapacities.getOrDefault(instanceIndex, 500);
    return Math.max(0, maxCapacity - instance.getSessionCount());
  }

  private int getInstanceIndex(GridInstance instance) {
    // Extract index from instance ID (assuming format like "grid-0", "grid-1", etc.)
    String id = instance.getId();
    try {
      if (id.contains("-")) {
        return Integer.parseInt(id.substring(id.lastIndexOf("-") + 1));
      }
    } catch (NumberFormatException e) {
      // Ignore and use hash-based index
    }
    return Math.abs(instance.getId().hashCode()) % 1000;
  }

  @Override
  public String getStrategyName() {
    return "Greedy";
  }
}
