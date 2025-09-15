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

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.openqa.selenium.internal.Require;

/**
 * Represents a backend Grid instance in the load balancer. Each GridInstance contains information
 * about a complete Grid deployment (Router-Distributor-SessionQueue-SessionMap-Nodes).
 */
public class GridInstance {

  public enum Status {
    HEALTHY,
    UNHEALTHY,
    DRAINING
  }

  private final String id;
  private final URI baseUri;
  private final AtomicReference<Status> status;
  private final AtomicReference<Instant> lastHealthCheck;
  private final AtomicInteger sessionCount;
  private final AtomicInteger failureCount;
  private final Instant createdAt;
  private volatile boolean enabled;

  public GridInstance(String id, URI baseUri) {
    this.id = Require.nonNull("Grid instance ID", id);
    this.baseUri = Require.nonNull("Grid instance base URI", baseUri);
    this.status = new AtomicReference<>(Status.HEALTHY);
    this.lastHealthCheck = new AtomicReference<>(Instant.now());
    this.sessionCount = new AtomicInteger(0);
    this.failureCount = new AtomicInteger(0);
    this.createdAt = Instant.now();
    this.enabled = true;
  }

  public String getId() {
    return id;
  }

  public URI getBaseUri() {
    return baseUri;
  }

  public Status getStatus() {
    return status.get();
  }

  public void setStatus(Status status) {
    this.status.set(Require.nonNull("Status", status));
  }

  public Instant getLastHealthCheck() {
    return lastHealthCheck.get();
  }

  public void updateLastHealthCheck() {
    this.lastHealthCheck.set(Instant.now());
  }

  public int getSessionCount() {
    return sessionCount.get();
  }

  public void incrementSessionCount() {
    sessionCount.incrementAndGet();
  }

  public void decrementSessionCount() {
    sessionCount.updateAndGet(count -> Math.max(0, count - 1));
  }

  public void setSessionCount(int count) {
    sessionCount.set(Math.max(0, count));
  }

  public int getFailureCount() {
    return failureCount.get();
  }

  public void incrementFailureCount() {
    failureCount.incrementAndGet();
  }

  public void resetFailureCount() {
    failureCount.set(0);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public boolean isHealthy() {
    return status.get() == Status.HEALTHY;
  }

  public boolean isDraining() {
    return status.get() == Status.DRAINING;
  }

  public boolean isUnhealthy() {
    return status.get() == Status.UNHEALTHY;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Returns true if this Grid instance is available for new sessions. A Grid instance is available
   * if it's healthy, not draining, and enabled.
   */
  public boolean isAvailableForNewSessions() {
    return isHealthy() && enabled;
  }

  /**
   * Returns the load factor for this Grid instance. Lower values indicate less load and higher
   * priority for new sessions.
   */
  public double getLoadFactor() {
    if (!isHealthy() || !enabled) {
      return Double.MAX_VALUE; // Unhealthy or disabled instances have maximum load
    }

    // Base load is session count, with failure count as penalty
    return sessionCount.get() + (failureCount.get() * 0.1);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GridInstance that = (GridInstance) o;
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @Override
  public String toString() {
    return String.format(
        "GridInstance{id='%s', baseUri=%s, status=%s, sessionCount=%d, failureCount=%d}",
        id, baseUri, status.get(), sessionCount.get(), failureCount.get());
  }
}
