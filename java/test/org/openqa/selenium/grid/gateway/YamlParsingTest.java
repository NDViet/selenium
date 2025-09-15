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

import org.junit.jupiter.api.Test;

class YamlParsingTest {

  @Test
  void shouldParseValidYamlContent() {
    String yamlContent =
        "routingRules:\n"
            + "  - match:\n"
            + "      browserName:\n"
            + "        - chrome\n"
            + "    distribute:\n"
            + "      - index: 0\n"
            + "        weight: 50\n"
            + "      - index: 1\n"
            + "        weight: 50\n";

    RoutingRulesConfig config = RoutingRulesConfig.fromYamlString(yamlContent);

    assertThat(config.hasRoutingRules()).isTrue();
    assertThat(config.getRoutingRules()).hasSize(1);

    RoutingRule rule = config.getRoutingRules().get(0);
    assertThat(rule.getMatch()).containsKey("browserName");
    assertThat(rule.getDistribute()).hasSize(2);
  }

  @Test
  void shouldHandleEmptyYamlContent() {
    RoutingRulesConfig config = RoutingRulesConfig.fromYamlString("");

    assertThat(config.hasRoutingRules()).isFalse();
    assertThat(config.getRoutingRules()).isEmpty();
  }

  @Test
  void shouldParseSampleYamlFormat() {
    String yamlContent =
        "routingRules:\n"
            + "  - matchCriteria:\n"
            + "      browserName: chrome\n"
            + "    distribute:\n"
            + "      - instanceIndex: 0\n"
            + "        weight: 60\n"
            + "      - instanceIndex: 1\n"
            + "        weight: 40\n";

    RoutingRulesConfig config = RoutingRulesConfig.fromYamlString(yamlContent);

    assertThat(config.hasRoutingRules()).isTrue();
    assertThat(config.getRoutingRules()).hasSize(1);

    RoutingRule rule = config.getRoutingRules().get(0);
    assertThat(rule.getMatch()).containsKey("browserName");
    assertThat(rule.getDistribute()).hasSize(2);
    assertThat(rule.getDistribute().get(0).getIndex()).isEqualTo(0);
    assertThat(rule.getDistribute().get(0).getWeight()).isEqualTo(60);
  }
}
