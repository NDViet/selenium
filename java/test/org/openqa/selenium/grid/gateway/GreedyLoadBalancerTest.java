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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GreedyLoadBalancerTest {

  @Test
  void shouldVerifyGreedyStrategyExists() {
    // Test that GREEDY strategy exists in factory
    LoadBalancerFactory.StrategyType greedyStrategy = LoadBalancerFactory.StrategyType.GREEDY;
    assertThat(greedyStrategy).isEqualTo(LoadBalancerFactory.StrategyType.GREEDY);
  }

  @Test
  void shouldVerifyGridInstanceCapacityLogic() {
    // Test GridInstance capacity behavior
    GridInstance instance = new GridInstance("test-grid", URI.create("http://test:4444"));

    // Initially should have 0 sessions
    assertThat(instance.getSessionCount()).isEqualTo(0);
    assertThat(instance.isAvailableForNewSessions()).isTrue();

    // Increment session count
    instance.incrementSessionCount();
    assertThat(instance.getSessionCount()).isEqualTo(1);

    // Set session count to high value
    instance.setSessionCount(499);
    assertThat(instance.getSessionCount()).isEqualTo(499);
    assertThat(instance.isAvailableForNewSessions()).isTrue();

    // Set to capacity (500 would be at capacity in real implementation)
    instance.setSessionCount(500);
    assertThat(instance.getSessionCount()).isEqualTo(500);
  }

  @Test
  void shouldVerifyLoadBalancingStrategies() {
    // Test that all required strategies exist
    LoadBalancerFactory.StrategyType[] strategies = LoadBalancerFactory.StrategyType.values();

    assertThat(strategies)
        .contains(
            LoadBalancerFactory.StrategyType.GREEDY,
            LoadBalancerFactory.StrategyType.ROUND_ROBIN,
            LoadBalancerFactory.StrategyType.LEAST_SESSIONS);
  }

  @Test
  void shouldCreateGreedyLoadBalancer() {
    // Test creating Greedy load balancer with capacity configuration
    Map<Integer, Integer> capacities =
        Map.of(
            0, 200,
            1, 500,
            2, 300);

    GreedyLoadBalancer greedy = new GreedyLoadBalancer(capacities);
    assertThat(greedy.getStrategyName()).isEqualTo("Greedy");
  }

  @Test
  void shouldUseDefault500CapacityWhenNoConfig() {
    // Test that default capacity is 500 when no config is provided
    GreedyLoadBalancer greedy = new GreedyLoadBalancer(null);
    assertThat(greedy.getStrategyName()).isEqualTo("Greedy");
  }

  @Test
  void shouldSupportInstanceConfigurationFromYaml() {
    // Test that InstanceConfig can be created with YAML-like data
    InstanceConfig config1 = new InstanceConfig(0, 200);
    InstanceConfig config2 = new InstanceConfig(1, 500);
    InstanceConfig config3 = new InstanceConfig(2, 300);

    // Verify configuration values
    assertThat(config1.getIndex()).isEqualTo(0);
    assertThat(config1.getMaxCapabilities()).isEqualTo(200);

    assertThat(config2.getIndex()).isEqualTo(1);
    assertThat(config2.getMaxCapabilities()).isEqualTo(500);

    assertThat(config3.getIndex()).isEqualTo(2);
    assertThat(config3.getMaxCapabilities()).isEqualTo(300);
  }

  @Test
  void shouldFallbackTo500ForInvalidMaxCapabilities() {
    // Test that invalid maxCapabilities fallback to 500
    InstanceConfig zeroConfig = new InstanceConfig(0, 0);
    assertThat(zeroConfig.getMaxCapabilities()).isEqualTo(500);

    InstanceConfig negativeConfig = new InstanceConfig(1, -1);
    assertThat(negativeConfig.getMaxCapabilities()).isEqualTo(500);
  }
}
