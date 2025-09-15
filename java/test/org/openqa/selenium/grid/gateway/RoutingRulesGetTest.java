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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingRulesGetTest {

  @Test
  void testToYamlStringWithNoConfiguration() {
    RoutingRulesConfig config = new RoutingRulesConfig(Collections.emptyList());

    String yamlContent = config.toYamlString();

    assertThat(yamlContent).isEqualTo("# No routing rules configured\n");
  }

  @Test
  void testToYamlStringWithConfiguration() {
    // Create routing rules config with sample data
    DistributionTarget target1 = new DistributionTarget(0, 50);
    DistributionTarget target2 = new DistributionTarget(1, 50);

    Map<String, List<String>> matchCriteria = Map.of("browserName", List.of("chrome"));
    RoutingRule rule = new RoutingRule(matchCriteria, List.of(target1, target2));

    InstanceConfig instance1 = new InstanceConfig(0, 100);
    InstanceConfig instance2 = new InstanceConfig(1, 200);

    RoutingRulesConfig config =
        new RoutingRulesConfig(List.of(rule), List.of(instance1, instance2));

    String yamlContent = config.toYamlString();

    assertThat(yamlContent).contains("routingRules:");
    assertThat(yamlContent).contains("browserName:");
    assertThat(yamlContent).contains("- chrome");
    assertThat(yamlContent).contains("distribute:");
    assertThat(yamlContent).contains("index: 0");
    assertThat(yamlContent).contains("weight: 50");
    assertThat(yamlContent).contains("instances:");
    assertThat(yamlContent).contains("maxCapabilities: 100");
  }

  @Test
  void testToYamlStringWithEmptyConfiguration() {
    RoutingRulesConfig config = new RoutingRulesConfig(Collections.emptyList());

    String yamlContent = config.toYamlString();

    assertThat(yamlContent).isEqualTo("# No routing rules configured\n");
  }
}
