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

package org.openqa.selenium.grid.sessionmap;

import static org.openqa.selenium.remote.http.Route.combine;
import static org.openqa.selenium.remote.http.Route.delete;
import static org.openqa.selenium.remote.http.Route.post;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.Routable;
import org.openqa.selenium.remote.http.Route;
import org.openqa.selenium.remote.tracing.Tracer;
import org.openqa.selenium.status.HasReadyState;

/**
 * Provides a stable API for looking up where on the Grid a particular webdriver instance is
 * running.
 *
 * <p>This class responds to the following URLs:
 *
 * <table summary="HTTP commands the SessionMap understands">
 * <tr>
 *   <th>Verb</th>
 *   <th>URL Template</th>
 *   <th>Meaning</th>
 * </tr>
 * <tr>
 *   <td>DELETE</td>
 *   <td>/se/grid/session/{sessionId}</td>
 *   <td>Removes a {@link URI} from the session map. Calling this method more than once for the same
 *     {@link SessionId} will not throw an error.</td>
 * </tr>
 * <tr>
 *   <td>GET</td>
 *   <td>/se/grid/session/{sessionId}</td>
 *   <td>Retrieves the {@link URI} associated the {@link SessionId}, or throws a
 *     {@link org.openqa.selenium.NoSuchSessionException} should the session not be present.</td>
 * </tr>
 * <tr>
 *   <td>POST</td>
 *   <td>/se/grid/session/{sessionId}</td>
 *   <td>Registers the session with session map. In theory, the session map never expires a session
 *     from its mappings, but realistically, sessions may end up being removed for many reasons.
 *     </td>
 * </tr>
 * </table>
 */
public abstract class SessionMap implements HasReadyState, Routable {

  protected final Tracer tracer;

  private final Route routes;
  private final ConcurrentMap<SessionId, Session> trackedSessions = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<SessionMetadata> sessionHistory = new CopyOnWriteArrayList<>();

  public static final String REASON_HTTP_REQUEST = "http-request";
  public static final String REASON_SESSION_CLOSED_EVENT = "session-closed-event";
  public static final String REASON_NODE_REMOVED = "node-removed";
  public static final String REASON_NODE_RESTARTED = "node-restarted";
  public static final String REASON_UNKNOWN = "unknown";

  public abstract boolean add(Session session);

  public abstract Session get(SessionId id) throws NoSuchSessionException;

  public void remove(SessionId id) {
    remove(id, REASON_UNKNOWN, Instant.now());
  }

  public void remove(SessionId id, String reason) {
    remove(id, reason, Instant.now());
  }

  public abstract void remove(SessionId id, String reason, Instant endedAt);

  public URI getUri(SessionId id) throws NoSuchSessionException {
    return get(id).getUri();
  }

  public SessionMap(Tracer tracer) {
    this.tracer = Require.nonNull("Tracer", tracer);

    Json json = new Json();
    routes =
        combine(
            Route.get("/se/grid/session/{sessionId}/uri")
                .to(params -> new GetSessionUri(this, sessionIdFrom(params))),
            post("/se/grid/session").to(() -> new AddToSessionMap(tracer, json, this)),
            Route.get("/se/grid/session/{sessionId}")
                .to(params -> new GetFromSessionMap(tracer, this, sessionIdFrom(params))),
            Route.get("/se/grid/sessions/history")
                .to(() -> new GetSessionHistory(tracer, this)),
            delete("/se/grid/session/{sessionId}")
                .to(params -> new RemoveFromSession(tracer, this, sessionIdFrom(params))));
  }

  private SessionId sessionIdFrom(Map<String, String> params) {
    return new SessionId(params.get("sessionId"));
  }

  @Override
  public boolean matches(HttpRequest req) {
    return routes.matches(req);
  }

  @Override
  public HttpResponse execute(HttpRequest req) {
    return routes.execute(req);
  }

  protected void trackSession(Session session) {
    trackedSessions.put(session.getId(), session);
  }

  protected void recordSessionClosed(SessionId id, Session removedSession, Instant endedAt, String reason) {
    String normalisedReason = normaliseReason(reason);
    Session session = removedSession != null ? removedSession : trackedSessions.remove(id);
    if (session != null) {
      sessionHistory.add(new SessionMetadata(session, endedAt, normalisedReason));
    } else {
      sessionHistory.add(new SessionMetadata(id, endedAt, normalisedReason));
    }
    trackedSessions.remove(id);
  }

  public List<SessionMetadata> getSessionHistory(SessionHistoryFilters filters) {
    Require.nonNull("Session history filters", filters);
    return getSessionHistory(
        filters.getSessionId(), filters.getCloseReason(), filters.getStartedAfter(), filters.getEndedAfter());
  }

  public List<SessionMetadata> getSessionHistory(
      Optional<SessionId> sessionId,
      Optional<String> reason,
      Optional<Instant> startedAfter,
      Optional<Instant> endedAfter) {

    return sessionHistory.stream()
        .filter(
            metadata ->
                sessionId.map(id -> id.equals(metadata.getSessionId())).orElse(true))
        .filter(
            metadata ->
                reason
                    .map(value -> metadata.getCloseReason().equalsIgnoreCase(value))
                    .orElse(true))
        .filter(
            metadata ->
                startedAfter
                    .map(start -> metadata.getStartTime() != null && !metadata.getStartTime().isBefore(start))
                    .orElse(true))
        .filter(
            metadata ->
                endedAfter
                    .map(end -> !metadata.getEndTime().isBefore(end))
                    .orElse(true))
        .collect(Collectors.toUnmodifiableList());
  }

  protected String normaliseReason(String reason) {
    return Optional.ofNullable(reason)
        .map(value -> value.trim().toLowerCase(Locale.ROOT))
        .filter(value -> !value.isEmpty())
        .orElse(REASON_UNKNOWN);
  }
}
