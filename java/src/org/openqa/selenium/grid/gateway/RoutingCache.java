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
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cache to track pending session creation requests to prevent multiple concurrent requests from
 * being routed to the same Grid instance.
 */
public class RoutingCache {

  private final ConcurrentMap<String, Instant> pendingRequests = new ConcurrentHashMap<>();
  private final Duration cacheTimeout;

  public RoutingCache(Duration cacheTimeout) {
    this.cacheTimeout = cacheTimeout;
  }

  public RoutingCache() {
    this(Duration.ofSeconds(30)); // Default 30 second timeout
  }

  /**
   * Marks an instance as having a pending session creation request.
   *
   * @param instanceId Grid instance ID
   * @return true if successfully marked, false if already pending
   */
  public boolean markPending(String instanceId) {
    cleanupExpired();
    return pendingRequests.putIfAbsent(instanceId, Instant.now()) == null;
  }

  /**
   * Removes the pending marker for an instance.
   *
   * @param instanceId Grid instance ID
   */
  public void clearPending(String instanceId) {
    pendingRequests.remove(instanceId);
  }

  /**
   * Checks if an instance has a pending session creation request.
   *
   * @param instanceId Grid instance ID
   * @return true if pending, false otherwise
   */
  public boolean isPending(String instanceId) {
    cleanupExpired();
    return pendingRequests.containsKey(instanceId);
  }

  /** Removes expired pending requests. */
  private void cleanupExpired() {
    Instant cutoff = Instant.now().minus(cacheTimeout);
    pendingRequests.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
  }

  /** Forces cleanup of expired entries. */
  public void forceCleanup() {
    cleanupExpired();
  }

  /** Clears all pending requests. */
  public void clear() {
    pendingRequests.clear();
  }

  /** Gets the number of pending requests. */
  public int size() {
    cleanupExpired();
    return pendingRequests.size();
  }
}
