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

package org.openqa.selenium.grid.sessionmap.local;

import static org.openqa.selenium.remote.RemoteTags.SESSION_ID;
import static org.openqa.selenium.remote.RemoteTags.SESSION_ID_EVENT;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.events.Event;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.data.NodeRemovedEvent;
import org.openqa.selenium.grid.data.NodeRestartedEvent;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.data.SessionClosedEvent;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.server.EventBusOptions;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.tracing.AttributeKey;
import org.openqa.selenium.remote.tracing.AttributeMap;
import org.openqa.selenium.remote.tracing.Span;
import org.openqa.selenium.remote.tracing.Tracer;

public class LocalSessionMap extends SessionMap {

  private static final Logger LOG = Logger.getLogger(LocalSessionMap.class.getName());

  private static final Duration REMOVAL_RETENTION = Duration.ofHours(1);

  private final EventBus bus;
  private final IndexedSessionMap knownSessions = new IndexedSessionMap();
  private final ConcurrentMap<SessionId, SessionRemovalInfo> removalHistory =
      new ConcurrentHashMap<>();

  public LocalSessionMap(Tracer tracer, EventBus bus) {
    super(tracer);

    this.bus = Require.nonNull("Event bus", bus);

    bus.addListener(
        SessionClosedEvent.listener(
            id -> removeWithCause(id, RemovalCause.SESSION_CLOSED, null)));

    bus.addListener(
        NodeRemovedEvent.listener(
            nodeStatus -> {
              batchRemoveByUri(
                  nodeStatus.getExternalUri(), NodeRemovedEvent.class, RemovalCause.NODE_REMOVED);
            }));

    bus.addListener(
        NodeRestartedEvent.listener(
            previousNodeStatus -> {
              batchRemoveByUri(
                  previousNodeStatus.getExternalUri(),
                  NodeRestartedEvent.class,
                  RemovalCause.NODE_RESTARTED);
            }));
  }

  public static SessionMap create(Config config) {
    Tracer tracer = new LoggingOptions(config).getTracer();
    EventBus bus = new EventBusOptions(config).getEventBus();

    return new LocalSessionMap(tracer, bus);
  }

  @Override
  public boolean isReady() {
    return bus.isReady();
  }

  @Override
  public boolean add(Session session) {
    Require.nonNull("Session", session);

    SessionId id = session.getId();
    knownSessions.put(id, session);
    removalHistory.remove(id);

    try (Span span = tracer.getCurrentContext().createSpan("local_sessionmap.add")) {
      AttributeMap attributeMap = tracer.createAttributeMap();
      attributeMap.put(AttributeKey.LOGGER_CLASS.getKey(), getClass().getName());
      SESSION_ID.accept(span, id);
      SESSION_ID_EVENT.accept(attributeMap, id);

      String sessionAddedMessage =
          String.format(
              "Added session to local Session Map, Id: %s, Node: %s", id, session.getUri());
      span.addEvent(sessionAddedMessage, attributeMap);
      LOG.info(sessionAddedMessage);
    }

    return true;
  }

  @Override
  public Session get(SessionId id) {
    Require.nonNull("Session ID", id);

    Session session = knownSessions.get(id);
    if (session == null) {
      SessionRemovalInfo removalInfo = removalHistory.get(id);
      if (removalInfo != null) {
        Instant now = Instant.now();
        if (removalInfo.isExpired(now)) {
          removalHistory.remove(id, removalInfo);
        } else {
          long secondsAgo = Math.max(0, removalInfo.secondsSince(now));
          throw new NoSuchSessionException(
              String.format(
                  "Session ID: %s was closed %d second(s) ago. Reason: %s",
                  id, secondsAgo, removalInfo.reason));
        }
      }
      throw new NoSuchSessionException("Unable to find session with ID: " + id);
    }
    return session;
  }

  @Override
  public void remove(SessionId id) {
    removeWithCause(
        id, RemovalCause.EXPLICIT_REMOVE, "Session removal requested through the SessionMap API.");
  }

  private void removeWithCause(SessionId id, RemovalCause cause, String detail) {
    Require.nonNull("Session ID", id);

    Session removedSession = knownSessions.remove(id);
    recordRemoval(id, removedSession, cause, detail);

    try (Span span = tracer.getCurrentContext().createSpan("local_sessionmap.remove")) {
      AttributeMap attributeMap = tracer.createAttributeMap();
      attributeMap.put(AttributeKey.LOGGER_CLASS.getKey(), getClass().getName());
      SESSION_ID.accept(span, id);
      SESSION_ID_EVENT.accept(attributeMap, id);

      String sessionDeletedMessage =
          String.format(
              "Deleted session from local Session Map, Id: %s, Node: %s (cause: %s)",
              id,
              removedSession != null ? String.valueOf(removedSession.getUri()) : "unidentified",
              detail != null ? detail : cause.defaultDescription);
      span.addEvent(sessionDeletedMessage, attributeMap);
      LOG.info(sessionDeletedMessage);
    }
  }

  private void batchRemoveByUri(
      URI externalUri, Class<? extends Event> eventClass, RemovalCause cause) {
    Set<SessionId> sessionsToRemove = knownSessions.getSessionsByUri(externalUri);

    if (sessionsToRemove.isEmpty()) {
      return; // Early return for empty operations - no tracing overhead
    }

    Map<SessionId, Session> removedSessions = knownSessions.batchRemove(sessionsToRemove);

    if (removedSessions.isEmpty()) {
      return;
    }

    Instant removalInstant = Instant.now();
    String reasonMessage = buildReasonMessage(cause, externalUri, eventClass);
    removedSessions.forEach(
        (sessionId, session) ->
            recordRemoval(sessionId, session, cause, reasonMessage, removalInstant));

    try (Span span = tracer.getCurrentContext().createSpan("local_sessionmap.batch_remove")) {
      AttributeMap attributeMap = tracer.createAttributeMap();
      attributeMap.put(AttributeKey.LOGGER_CLASS.getKey(), getClass().getName());
      attributeMap.put("event.class", eventClass.getName());
      attributeMap.put("node.uri", externalUri.toString());
      attributeMap.put("sessions.count", removedSessions.size());

      String batchRemoveMessage =
          String.format(
              "Batch removed %d sessions from local Session Map for Node %s (triggered by %s)",
              removedSessions.size(), externalUri, eventClass.getSimpleName());
      span.addEvent(batchRemoveMessage, attributeMap);
      LOG.info(batchRemoveMessage);
    }
  }

  private String buildReasonMessage(
      RemovalCause cause, URI externalUri, Class<? extends Event> eventClass) {
    if (cause == RemovalCause.NODE_REMOVED) {
      return String.format("Node %s was removed from the grid.", externalUri);
    }
    if (cause == RemovalCause.NODE_RESTARTED) {
      return String.format("Node %s was restarted.", externalUri);
    }
    return String.format("%s (triggered by %s)", cause.defaultDescription, eventClass.getSimpleName());
  }

  private static class IndexedSessionMap {
    private final ConcurrentMap<SessionId, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<URI, Set<SessionId>> sessionsByUri = new ConcurrentHashMap<>();
    private final Object coordinationLock = new Object();

    public Session get(SessionId id) {
      return sessions.get(id);
    }

    public Session put(SessionId id, Session session) {
      synchronized (coordinationLock) {
        Session previous = sessions.put(id, session);

        if (previous != null && previous.getUri() != null) {
          cleanupUriIndex(previous.getUri(), id);
        }

        URI sessionUri = session.getUri();
        if (sessionUri != null) {
          sessionsByUri.computeIfAbsent(sessionUri, k -> ConcurrentHashMap.newKeySet()).add(id);
        }

        return previous;
      }
    }

    public Session remove(SessionId id) {
      synchronized (coordinationLock) {
        Session removed = sessions.remove(id);

        if (removed != null && removed.getUri() != null) {
          cleanupUriIndex(removed.getUri(), id);
        }

        return removed;
      }
    }

    public Map<SessionId, Session> batchRemove(Set<SessionId> sessionIds) {
      synchronized (coordinationLock) {
        Map<URI, Set<SessionId>> uriToSessionIds = new HashMap<>();
        Map<SessionId, Session> removedSessions = new HashMap<>();

        // Single loop: remove sessions and collect URI mappings in one pass
        for (SessionId id : sessionIds) {
          Session session = sessions.remove(id);
          if (session != null) {
            removedSessions.put(id, session);
            if (session.getUri() != null) {
              uriToSessionIds.computeIfAbsent(session.getUri(), k -> new HashSet<>()).add(id);
            }
          }
        }

        // Clean up URI index for all affected URIs
        for (Map.Entry<URI, Set<SessionId>> entry : uriToSessionIds.entrySet()) {
          cleanupUriIndex(entry.getKey(), entry.getValue());
        }

        return removedSessions;
      }
    }

    private void cleanupUriIndex(URI uri, SessionId sessionId) {
      sessionsByUri.computeIfPresent(
          uri,
          (key, sessionIds) -> {
            sessionIds.remove(sessionId);
            return sessionIds.isEmpty() ? null : sessionIds;
          });
    }

    private void cleanupUriIndex(URI uri, Set<SessionId> sessionIdsToRemove) {
      sessionsByUri.computeIfPresent(
          uri,
          (key, sessionIds) -> {
            sessionIds.removeAll(sessionIdsToRemove);
            return sessionIds.isEmpty() ? null : sessionIds;
          });
    }

    public Set<SessionId> getSessionsByUri(URI uri) {
      Set<SessionId> result = sessionsByUri.get(uri);
      return (result != null && !result.isEmpty()) ? result : Set.of();
    }

    public Set<Map.Entry<SessionId, Session>> entrySet() {
      return Collections.unmodifiableSet(sessions.entrySet());
    }

    public Collection<Session> values() {
      return Collections.unmodifiableCollection(sessions.values());
    }

    public int size() {
      return sessions.size();
    }

    public boolean isEmpty() {
      return sessions.isEmpty();
    }

    public void clear() {
      synchronized (coordinationLock) {
        sessions.clear();
        sessionsByUri.clear();
      }
    }
  }

  private void recordRemoval(
      SessionId id, Session removedSession, RemovalCause cause, String detail) {
    recordRemoval(id, removedSession, cause, detail, Instant.now());
  }

  private void recordRemoval(
      SessionId id,
      Session removedSession,
      RemovalCause cause,
      String detail,
      Instant removedAt) {
    if (removedSession == null) {
      return;
    }

    String reason = (detail != null && !detail.isBlank()) ? detail : cause.defaultDescription;
    removalHistory.put(id, new SessionRemovalInfo(removedAt, reason));
  }

  private enum RemovalCause {
    EXPLICIT_REMOVE("Session was explicitly removed from the session map."),
    SESSION_CLOSED("Session closed event received from the node."),
    NODE_REMOVED("Node was removed from the grid."),
    NODE_RESTARTED("Node was restarted.");

    private final String defaultDescription;

    RemovalCause(String defaultDescription) {
      this.defaultDescription = defaultDescription;
    }
  }

  private static class SessionRemovalInfo {
    private final Instant removedAt;
    private final String reason;

    private SessionRemovalInfo(Instant removedAt, String reason) {
      this.removedAt = removedAt;
      this.reason = reason;
    }

    private boolean isExpired(Instant reference) {
      return removedAt.plus(REMOVAL_RETENTION).isBefore(reference);
    }

    private long secondsSince(Instant reference) {
      return Duration.between(removedAt, reference).getSeconds();
    }
  }
}
