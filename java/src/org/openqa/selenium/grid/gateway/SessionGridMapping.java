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

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.SessionId;

/**
 * Manages the mapping between WebDriver sessions and Grid instances. This ensures session affinity
 * - requests for a specific session are always routed to the same Grid instance where the session
 * was created.
 *
 * <p>This class is thread-safe and designed to be shared across multiple components.
 */
public class SessionGridMapping {

  private static volatile SessionGridMapping INSTANCE;
  private static final Object LOCK = new Object();

  private static final Logger LOG = Logger.getLogger(SessionGridMapping.class.getName());

  /** Represents a session mapping entry with metadata. */
  public static class SessionMapping {
    private final SessionId sessionId;
    private final String gridInstanceId;
    private final Instant createdAt;
    private volatile Instant lastAccessedAt;

    public SessionMapping(SessionId sessionId, String gridInstanceId) {
      this.sessionId = Require.nonNull("Session ID", sessionId);
      this.gridInstanceId = Require.nonNull("Grid instance ID", gridInstanceId);
      this.createdAt = Instant.now();
      this.lastAccessedAt = Instant.now();
    }

    public SessionId getSessionId() {
      return sessionId;
    }

    public String getGridInstanceId() {
      return gridInstanceId;
    }

    public Instant getCreatedAt() {
      return createdAt;
    }

    public Instant getLastAccessedAt() {
      return lastAccessedAt;
    }

    public void updateLastAccessed() {
      this.lastAccessedAt = Instant.now();
    }

    @Override
    public String toString() {
      return String.format(
          "SessionMapping{sessionId=%s, gridInstanceId='%s', createdAt=%s}",
          sessionId, gridInstanceId, createdAt);
    }
  }

  private final ConcurrentMap<SessionId, SessionMapping> sessionMappings;
  private final GridInstanceRegistry gridInstanceRegistry;

  public SessionGridMapping(GridInstanceRegistry gridInstanceRegistry) {
    this.sessionMappings = new ConcurrentHashMap<>();
    this.gridInstanceRegistry = Require.nonNull("Grid instance registry", gridInstanceRegistry);
  }

  /**
   * Gets or creates a shared instance of SessionGridMapping. This ensures all components use the
   * same session mapping.
   */
  public static SessionGridMapping getInstance(GridInstanceRegistry gridInstanceRegistry) {
    if (INSTANCE == null) {
      synchronized (LOCK) {
        if (INSTANCE == null) {
          INSTANCE = new SessionGridMapping(gridInstanceRegistry);
          LOG.info("Created shared SessionGridMapping instance");
        }
      }
    }
    return INSTANCE;
  }

  /**
   * Gets the existing shared instance, if available. Returns empty if no instance has been created
   * yet.
   */
  public static Optional<SessionGridMapping> getExistingInstance() {
    return Optional.ofNullable(INSTANCE);
  }

  /**
   * Maps a session to a Grid instance. This should be called when a new session is successfully
   * created.
   */
  public void mapSession(SessionId sessionId, String gridInstanceId) {
    Require.nonNull("Session ID", sessionId);
    Require.nonNull("Grid instance ID", gridInstanceId);

    SessionMapping mapping = new SessionMapping(sessionId, gridInstanceId);
    sessionMappings.put(sessionId, mapping);

    // Notify the registry that a session was created
    gridInstanceRegistry.onSessionCreated(gridInstanceId);

    LOG.info(String.format("Mapped session %s to Grid instance %s", sessionId, gridInstanceId));
  }

  /**
   * Gets the Grid instance ID for a given session. Updates the last accessed time for the session.
   */
  public Optional<String> getGridInstanceId(SessionId sessionId) {
    SessionMapping mapping = sessionMappings.get(sessionId);
    if (mapping != null) {
      mapping.updateLastAccessed();
      return Optional.of(mapping.getGridInstanceId());
    }
    return Optional.empty();
  }

  /** Gets the complete session mapping for a given session. */
  public Optional<SessionMapping> getSessionMapping(SessionId sessionId) {
    SessionMapping mapping = sessionMappings.get(sessionId);
    if (mapping != null) {
      mapping.updateLastAccessed();
      return Optional.of(mapping);
    }
    return Optional.empty();
  }

  /** Removes a session mapping. This should be called when a session ends or is deleted. */
  public void removeSession(SessionId sessionId) {
    SessionMapping removed = sessionMappings.remove(sessionId);
    if (removed != null) {
      // Notify the registry that a session ended
      gridInstanceRegistry.onSessionEnded(removed.getGridInstanceId());

      LOG.info(
          String.format(
              "Removed session mapping for %s from Grid instance %s",
              sessionId, removed.getGridInstanceId()));
    }
  }

  /**
   * Removes all session mappings for a specific Grid instance. This should be called when a Grid
   * instance is removed or becomes permanently unavailable.
   */
  public void removeSessionsForGridInstance(String gridInstanceId) {
    Require.nonNull("Grid instance ID", gridInstanceId);

    int removedCount = 0;
    for (Map.Entry<SessionId, SessionMapping> entry : sessionMappings.entrySet()) {
      if (gridInstanceId.equals(entry.getValue().getGridInstanceId())) {
        sessionMappings.remove(entry.getKey());
        removedCount++;
      }
    }

    if (removedCount > 0) {
      LOG.info(
          String.format(
              "Removed %d session mappings for Grid instance %s", removedCount, gridInstanceId));
    }
  }

  /** Gets the total number of active session mappings. */
  public int getActiveMappingCount() {
    return sessionMappings.size();
  }

  /** Gets the number of sessions mapped to a specific Grid instance. */
  public long getSessionCountForGridInstance(String gridInstanceId) {
    return sessionMappings.values().stream()
        .filter(mapping -> gridInstanceId.equals(mapping.getGridInstanceId()))
        .count();
  }

  /** Checks if a session is mapped to any Grid instance. */
  public boolean hasSession(SessionId sessionId) {
    return sessionMappings.containsKey(sessionId);
  }

  /** Gets all current session mappings. This is primarily for monitoring and debugging purposes. */
  public Map<SessionId, SessionMapping> getAllMappings() {
    return Map.copyOf(sessionMappings);
  }

  /** Clears all session mappings. This should only be used for testing or emergency cleanup. */
  public void clear() {
    int clearedCount = sessionMappings.size();
    sessionMappings.clear();

    if (clearedCount > 0) {
      LOG.warning(String.format("Cleared %d session mappings", clearedCount));
    }
  }

  /** Resets the singleton instance. This should only be used for testing. */
  public static void resetInstance() {
    synchronized (LOCK) {
      INSTANCE = null;
      LOG.info("Reset shared SessionGridMapping instance");
    }
  }
}
