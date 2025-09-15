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

package org.openqa.selenium.grid.gateway.example;

import java.util.logging.Logger;
import org.openqa.selenium.grid.gateway.GatewayOptions;
import org.openqa.selenium.remote.http.HttpClient;

/** Example of how to launch the Grid Gateway programmatically. */
public class LaunchGateway {

  private static final Logger LOG = Logger.getLogger(LaunchGateway.class.getName());

  public static void main(String[] args) throws Exception {
    // Configure the gateway options
    GatewayOptions gatewayOptions = new GatewayOptions();
    // Grid instances are now configured through gatewayOptions
    // In real usage, these would be set via configuration or command line

    // Create HTTP client factory
    HttpClient.Factory httpClientFactory = HttpClient.Factory.createDefault();

    // Simplified example - actual implementation would need proper configuration
    LOG.info("Gateway example - implementation simplified");
    LOG.info("Grid instances: " + gatewayOptions.getGridInstances());
    LOG.info("Load balancing strategy: " + gatewayOptions.getLoadBalancingStrategy());
    LOG.info("In real usage, configure with proper Tracer, ServerOptions, etc.");

    // Example would create:
    // - Gateway with proper configuration
    // - NettyServer with proper server options

    // For now, just demonstrate the concept
    Thread.sleep(1000);
    LOG.info("Gateway example completed");
  }
}
