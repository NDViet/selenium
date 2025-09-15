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

import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.grid.data.DefaultSlotMatcher;
import org.openqa.selenium.grid.data.DistributorStatus;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.SlotMatcher;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

/**
 * Utility class for filtering Grid instances based on capability matching. Reuses the existing
 * SlotMatcher logic from the Distributor role.
 */
public class CapabilityMatcher {

  private static final Logger LOG = Logger.getLogger(CapabilityMatcher.class.getName());
  private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);

  private final SlotMatcher slotMatcher;
  private final HttpClient.Factory httpClientFactory;
  private final Json json;

  public CapabilityMatcher() {
    this(new DefaultSlotMatcher(), null);
  }

  public CapabilityMatcher(SlotMatcher slotMatcher) {
    this(slotMatcher, null);
  }

  public CapabilityMatcher(SlotMatcher slotMatcher, HttpClient.Factory httpClientFactory) {
    this.slotMatcher = slotMatcher;
    this.httpClientFactory = httpClientFactory;
    this.json = new Json();
  }

  /**
   * Filters Grid instances to only those that have available slots matching the requested
   * capabilities.
   *
   * @param instances List of Grid instances to filter
   * @param requestedCapabilities Capabilities to match against
   * @return Filtered list of instances with matching capabilities
   */
  public List<GridInstance> filterByCapabilities(
      List<GridInstance> instances, Capabilities requestedCapabilities) {
    if (requestedCapabilities == null || httpClientFactory == null) {
      return instances;
    }

    return instances.stream()
        .filter(instance -> hasMatchingCapabilities(instance, requestedCapabilities))
        .collect(Collectors.toList());
  }

  /** Checks if a Grid instance has available slots that match the requested capabilities. */
  private boolean hasMatchingCapabilities(
      GridInstance instance, Capabilities requestedCapabilities) {
    if (httpClientFactory == null) {
      return true;
    }

    try {
      DistributorStatus status = queryDistributorStatus(instance);
      if (status == null) {
        return true;
      }

      return status.getNodes().stream()
          .anyMatch(nodeStatus -> nodeHasMatchingCapabilities(nodeStatus, requestedCapabilities));

    } catch (Exception e) {
      LOG.log(Level.FINE, "Failed to check capabilities for instance " + instance.getId(), e);
      return true;
    }
  }

  private boolean nodeHasMatchingCapabilities(
      NodeStatus nodeStatus, Capabilities requestedCapabilities) {
    return nodeStatus.hasCapacity()
        && nodeStatus.getSlots().stream()
            .anyMatch(
                slot ->
                    slot.getSession() == null
                        && slotMatcher.matches(slot.getStereotype(), requestedCapabilities));
  }

  private DistributorStatus queryDistributorStatus(GridInstance instance) {
    try {
      ClientConfig config =
          ClientConfig.defaultConfig().baseUri(instance.getBaseUri()).readTimeout(QUERY_TIMEOUT);

      try (HttpClient client = httpClientFactory.createClient(config)) {
        HttpRequest request = new HttpRequest(HttpMethod.GET, "/se/grid/distributor/status");
        HttpResponse response = client.execute(request);

        if (response.getStatus() == 200) {
          String content;
          try (java.io.Reader reader = Contents.reader(response)) {
            content =
                new java.io.BufferedReader(reader)
                    .lines()
                    .collect(java.util.stream.Collectors.joining("\n"));
          }
          return json.toType(content, DistributorStatus.class);
        }
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "Failed to query distributor status for " + instance.getId(), e);
    }

    return null;
  }
}
