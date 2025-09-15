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
import java.util.Map;
import org.openqa.selenium.Capabilities;

/** Represents a routing rule that matches capabilities and defines distribution targets. */
public class RoutingRule {

  private final Map<String, List<String>> match;
  private final List<DistributionTarget> distribute;
  private final boolean enabled;

  public RoutingRule(Map<String, List<String>> match, List<DistributionTarget> distribute) {
    this(match, distribute, true);
  }

  public RoutingRule(
      Map<String, List<String>> match, List<DistributionTarget> distribute, boolean enabled) {
    this.match = match != null ? match : Map.of();
    this.distribute = distribute != null ? distribute : List.of();
    this.enabled = enabled;
  }

  /** Check if the given capabilities match this routing rule. */
  public boolean matches(Capabilities capabilities) {
    // Disabled rules never match
    if (!enabled) {
      return false;
    }

    // Empty match criteria means default fallback rule
    if (match.isEmpty()) {
      return true;
    }

    // All match criteria must be satisfied
    for (Map.Entry<String, List<String>> entry : match.entrySet()) {
      String capabilityName = entry.getKey();
      List<String> expectedValues = entry.getValue();

      Object actualValue = capabilities.getCapability(capabilityName);
      if (actualValue == null) {
        return false;
      }

      String actualValueStr = actualValue.toString();
      if (!expectedValues.contains(actualValueStr)) {
        return false;
      }
    }

    return true;
  }

  public Map<String, List<String>> getMatch() {
    return match;
  }

  public List<DistributionTarget> getDistribute() {
    return distribute;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
