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

import java.util.Objects;

/**
 * Represents a distribution rule for routing in the Gateway. Specifies which Grid instance (by
 * index) should receive traffic and with what weight.
 */
public class DistributeRule {

  private final int index;
  private final int weight;

  public DistributeRule(int index, int weight) {
    if (index < 0) {
      throw new IllegalArgumentException("Index must be non-negative, got: " + index);
    }
    if (weight <= 0) {
      throw new IllegalArgumentException("Weight must be positive, got: " + weight);
    }
    this.index = index;
    this.weight = weight;
  }

  /**
   * Get the Grid instance index (0-based) that this rule applies to.
   *
   * @return the Grid instance index
   */
  public int getIndex() {
    return index;
  }

  /**
   * Get the weight for this distribution rule. Higher weights mean more traffic will be routed to
   * this instance.
   *
   * @return the weight value
   */
  public int getWeight() {
    return weight;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DistributeRule that = (DistributeRule) o;
    return index == that.index && weight == that.weight;
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, weight);
  }

  @Override
  public String toString() {
    return "DistributeRule{" + "index=" + index + ", weight=" + weight + '}';
  }
}
