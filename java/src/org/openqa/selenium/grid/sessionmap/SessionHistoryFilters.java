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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpRequest;

public class SessionHistoryFilters {

  private final Optional<SessionId> sessionId;
  private final Optional<String> closeReason;
  private final Optional<Instant> startedAfter;
  private final Optional<Instant> endedAfter;

  private SessionHistoryFilters(
      Optional<SessionId> sessionId,
      Optional<String> closeReason,
      Optional<Instant> startedAfter,
      Optional<Instant> endedAfter) {
    this.sessionId = sessionId;
    this.closeReason = closeReason;
    this.startedAfter = startedAfter;
    this.endedAfter = endedAfter;
  }

  public static SessionHistoryFilters fromRequest(HttpRequest req) {
    Optional<SessionId> sessionId =
        Optional.ofNullable(req.getQueryParameter("sessionId"))
            .or(() -> Optional.ofNullable(req.getQueryParameter("id")))
            .filter(value -> !value.isBlank())
            .map(SessionId::new);

    Optional<String> reason =
        Optional.ofNullable(req.getQueryParameter("reason"))
            .filter(value -> !value.isBlank())
            .map(value -> value.toLowerCase(Locale.ROOT));

    Optional<Instant> startedAfter = parseInstant(req.getQueryParameter("startedAfter"));
    Optional<Instant> endedAfter =
        parseInstant(req.getQueryParameter("endedAfter"))
            .or(() -> parseInstant(req.getQueryParameter("timestamp")));

    return new SessionHistoryFilters(sessionId, reason, startedAfter, endedAfter);
  }

  private static Optional<Instant> parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }

    try {
      return Optional.of(Instant.parse(value));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Unable to parse timestamp: " + value, e);
    }
  }

  public Optional<SessionId> getSessionId() {
    return sessionId;
  }

  public Optional<String> getCloseReason() {
    return closeReason;
  }

  public Optional<Instant> getStartedAfter() {
    return startedAfter;
  }

  public Optional<Instant> getEndedAfter() {
    return endedAfter;
  }
}

