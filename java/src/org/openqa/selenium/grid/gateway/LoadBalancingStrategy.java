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
import org.openqa.selenium.Capabilities;

/** Interface for load balancing strategies that select Grid instances for new sessions. */
public interface LoadBalancingStrategy {

  /**
   * Select a Grid instance from the available instances for a new session.
   *
   * @param availableInstances List of healthy Grid instances available for new sessions
   * @param requestedCapabilities Capabilities requested for the new session (may be null)
   * @return Selected Grid instance, or empty if no suitable instance found
   */
  Optional<GridInstance> selectGridInstance(
      List<GridInstance> availableInstances, Capabilities requestedCapabilities);

  /**
   * Get the name of this load balancing strategy.
   *
   * @return Strategy name for logging and identification
   */
  String getStrategyName();
}
