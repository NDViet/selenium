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

package org.openqa.selenium.grid.commands;

import com.google.common.collect.ImmutableMap;
import org.openqa.selenium.grid.config.MapConfig;

class DefaultGatewayConfig extends MapConfig {

  DefaultGatewayConfig() {
    super(
        ImmutableMap.of(
            "gateway",
            ImmutableMap.of(
                "grid-instances", "http://localhost:4444",
                "load-balancing-strategy", "LEAST_SESSIONS",
                "max-retry-attempts", "3",
                "health-check-interval", "30",
                "health-check-timeout", "5",
                "session-cleanup-interval", "300",
                "enable-metrics", "true")));
  }
}
