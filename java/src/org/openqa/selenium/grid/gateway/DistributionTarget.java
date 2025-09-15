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

/** Represents a distribution target with instance index and weight for load balancing. */
public class DistributionTarget {

  private final int index;
  private final int weight;

  public DistributionTarget(int index, int weight) {
    this.index = index;
    this.weight = Math.max(0, weight); // Ensure non-negative weight
  }

  public int getIndex() {
    return index;
  }

  public int getWeight() {
    return weight;
  }

  @Override
  public String toString() {
    return String.format("DistributionTarget{index=%d, weight=%d}", index, weight);
  }
}
