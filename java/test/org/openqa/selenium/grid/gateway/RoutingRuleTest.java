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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;

class RoutingRuleTest {

  private Capabilities chromeCapabilities;
  private Capabilities firefoxCapabilities;
  private Capabilities safariCapabilities;

  @BeforeEach
  void setUp() {
    chromeCapabilities =
        new ImmutableCapabilities(
            Map.of("browserName", "chrome", "browserVersion", "120.0", "platformName", "linux"));

    firefoxCapabilities =
        new ImmutableCapabilities(
            Map.of("browserName", "firefox", "browserVersion", "119.0", "platformName", "windows"));

    safariCapabilities =
        new ImmutableCapabilities(
            Map.of("browserName", "safari", "browserVersion", "17.0", "platformName", "mac"));
  }

  @Test
  void shouldCreateRoutingRuleWithValidParameters() {
    // Given
    Map<String, List<String>> matchCriteria = Map.of("browserName", Arrays.asList("chrome"));
    List<DistributionTarget> distributeTargets =
        Arrays.asList(
            new DistributionTarget(0, 50),
            new DistributionTarget(1, 30),
            new DistributionTarget(2, 20));

    // When
    RoutingRule rule = new RoutingRule(matchCriteria, distributeTargets);

    // Then
    assertThat(rule.getMatch()).isEqualTo(matchCriteria);
    assertThat(rule.getDistribute()).isEqualTo(distributeTargets);
  }

  @Test
  void shouldMatchCapabilitiesWithExactBrowserMatch() {
    // Given
    Map<String, List<String>> matchCriteria = Map.of("browserName", Arrays.asList("chrome"));
    List<DistributionTarget> distributeTargets = Arrays.asList(new DistributionTarget(0, 100));
    RoutingRule rule = new RoutingRule(matchCriteria, distributeTargets);

    // When
    boolean matches = rule.matches(chromeCapabilities);

    // Then
    assertThat(matches).isTrue();
  }

  @Test
  void shouldNotMatchCapabilitiesWithDifferentBrowser() {
    // Given
    Map<String, List<String>> matchCriteria = Map.of("browserName", Arrays.asList("chrome"));
    List<DistributionTarget> distributeTargets = Arrays.asList(new DistributionTarget(0, 100));
    RoutingRule rule = new RoutingRule(matchCriteria, distributeTargets);

    // When
    boolean matches = rule.matches(firefoxCapabilities);

    // Then
    assertThat(matches).isFalse();
  }

  @Test
  void shouldMatchCapabilitiesWithMultipleCriteria() {
    // Given
    Map<String, List<String>> matchCriteria =
        Map.of(
            "platformName", Arrays.asList("linux"),
            "browserName", Arrays.asList("chrome"));
    List<DistributionTarget> distributeTargets = Arrays.asList(new DistributionTarget(0, 100));
    RoutingRule rule = new RoutingRule(matchCriteria, distributeTargets);

    // When
    boolean matches = rule.matches(chromeCapabilities);

    // Then
    assertThat(matches).isTrue();
  }

  @Test
  void shouldMatchEmptyMatchCriteria() {
    // Given - Empty match criteria should match everything (default rule)
    Map<String, List<String>> matchCriteria = Collections.emptyMap();
    List<DistributionTarget> distributeTargets = Arrays.asList(new DistributionTarget(0, 100));
    RoutingRule rule = new RoutingRule(matchCriteria, distributeTargets);

    // When/Then - Should match any capabilities
    assertThat(rule.matches(chromeCapabilities)).isTrue();
    assertThat(rule.matches(firefoxCapabilities)).isTrue();
    assertThat(rule.matches(safariCapabilities)).isTrue();
  }

  @Test
  void shouldMatchMultipleBrowserValues() {
    // Given
    Map<String, List<String>> matchCriteria =
        Map.of("browserName", Arrays.asList("chrome", "firefox"));
    List<DistributionTarget> distributeTargets = Arrays.asList(new DistributionTarget(0, 100));
    RoutingRule rule = new RoutingRule(matchCriteria, distributeTargets);

    // When/Then
    assertThat(rule.matches(chromeCapabilities)).isTrue();
    assertThat(rule.matches(firefoxCapabilities)).isTrue();
    assertThat(rule.matches(safariCapabilities)).isFalse();
  }

  @Test
  void shouldNotMatchWhenCapabilityMissing() {
    // Given
    Map<String, List<String>> matchCriteria =
        Map.of("nonExistentCapability", Arrays.asList("value"));
    List<DistributionTarget> distributeTargets = Arrays.asList(new DistributionTarget(0, 100));
    RoutingRule rule = new RoutingRule(matchCriteria, distributeTargets);

    // When/Then
    assertThat(rule.matches(chromeCapabilities)).isFalse();
  }

  @Test
  void shouldProvideStringRepresentation() {
    // Given
    Map<String, List<String>> matchCriteria = Map.of("browserName", Arrays.asList("chrome"));
    List<DistributionTarget> distributeTargets =
        Arrays.asList(new DistributionTarget(0, 50), new DistributionTarget(1, 50));
    RoutingRule rule = new RoutingRule(matchCriteria, distributeTargets);

    // When
    String toString = rule.toString();

    // Then
    assertThat(toString).isNotNull();
    assertThat(toString).isNotEmpty();
  }
}
