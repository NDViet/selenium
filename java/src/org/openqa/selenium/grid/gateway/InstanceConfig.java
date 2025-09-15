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

import org.openqa.selenium.internal.Require;

/**
 * Configuration for a Grid instance capacity. Represents the YAML configuration: instances: -
 * index: 0 maxCapabilities: 200
 */
public class InstanceConfig {

  private final int index;
  private final int maxCapabilities;

  public InstanceConfig(int index, int maxCapabilities) {
    this.index = Require.nonNegative("Instance index", index);
    // If maxCapabilities <= 0, fallback to default 500 instead of breaking the system
    this.maxCapabilities = maxCapabilities > 0 ? maxCapabilities : 500;
  }

  public int getIndex() {
    return index;
  }

  public int getMaxCapabilities() {
    return maxCapabilities;
  }

  @Override
  public String toString() {
    return String.format("InstanceConfig{index=%d, maxCapabilities=%d}", index, maxCapabilities);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    InstanceConfig that = (InstanceConfig) o;
    return index == that.index && maxCapabilities == that.maxCapabilities;
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(index, maxCapabilities);
  }
}
