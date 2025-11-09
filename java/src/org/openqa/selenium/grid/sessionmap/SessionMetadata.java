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

import static java.util.Collections.unmodifiableMap;

import java.io.Serializable;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.JsonInput;
import org.openqa.selenium.remote.SessionId;

/**
 * Metadata tracked for a session after it has ended. It captures when the session started, when it
 * finished, why it was closed, and the last known URI that hosted it.
 */
public class SessionMetadata implements Serializable {

  private final SessionId sessionId;
  private final URI uri;
  private final Instant startTime;
  private final Instant endTime;
  private final String closeReason;

  SessionMetadata(Session session, Instant endTime, String closeReason) {
    this(
        Require.nonNull("Session", session).getId(),
        session.getUri(),
        session.getStartTime(),
        endTime,
        closeReason);
  }

  SessionMetadata(SessionId sessionId, Instant endTime, String closeReason) {
    this(sessionId, null, null, endTime, closeReason);
  }

  private SessionMetadata(
      SessionId sessionId, URI uri, Instant startTime, Instant endTime, String closeReason) {
    this.sessionId = Require.nonNull("Session id", sessionId);
    this.uri = uri;
    this.startTime = startTime;
    this.endTime = Require.nonNull("Session end time", endTime);
    this.closeReason = Require.nonNull("Close reason", closeReason);
  }

  public SessionId getSessionId() {
    return sessionId;
  }

  public URI getUri() {
    return uri;
  }

  public Instant getStartTime() {
    return startTime;
  }

  public Instant getEndTime() {
    return endTime;
  }

  public String getCloseReason() {
    return closeReason;
  }

  private Map<String, Object> toJson() {
    Map<String, Object> toReturn = new TreeMap<>();
    toReturn.put("sessionId", sessionId);
    if (uri != null) {
      toReturn.put("uri", uri);
    }
    if (startTime != null) {
      toReturn.put("startTime", startTime);
    }
    toReturn.put("endTime", endTime);
    toReturn.put("closeReason", closeReason);
    return unmodifiableMap(toReturn);
  }

  private static SessionMetadata fromJson(JsonInput input) {
    SessionId sessionId = null;
    URI uri = null;
    Instant start = null;
    Instant end = null;
    String reason = null;

    input.beginObject();
    while (input.hasNext()) {
      switch (input.nextName()) {
        case "sessionId":
          sessionId = input.read(SessionId.class);
          break;

        case "uri":
          uri = input.read(URI.class);
          break;

        case "startTime":
          start = input.read(Instant.class);
          break;

        case "endTime":
          end = input.read(Instant.class);
          break;

        case "closeReason":
          reason = input.read(String.class);
          break;

        default:
          input.skipValue();
          break;
      }
    }
    input.endObject();

    SessionMetadata metadata =
        new SessionMetadata(
            Require.nonNull("Session id", sessionId), end, Require.nonNull("Close reason", reason));
    if (uri != null || start != null) {
      metadata =
          new SessionMetadata(
              sessionId,
              uri,
              start,
              Require.nonNull("Session end time", end),
              Require.nonNull("Close reason", reason));
    }
    return metadata;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SessionMetadata)) {
      return false;
    }

    SessionMetadata other = (SessionMetadata) o;
    return Objects.equals(sessionId, other.sessionId)
        && Objects.equals(uri, other.uri)
        && Objects.equals(startTime, other.startTime)
        && Objects.equals(endTime, other.endTime)
        && Objects.equals(closeReason, other.closeReason);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sessionId, uri, startTime, endTime, closeReason);
  }
}

