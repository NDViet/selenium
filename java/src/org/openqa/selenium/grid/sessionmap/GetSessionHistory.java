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

import static org.openqa.selenium.remote.http.Contents.asJson;
import static org.openqa.selenium.remote.tracing.HttpTracing.newSpanAsChildOf;
import static org.openqa.selenium.remote.tracing.Tags.HTTP_REQUEST;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.List;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.tracing.Span;
import org.openqa.selenium.remote.tracing.Tracer;

class GetSessionHistory implements HttpHandler {

  private final Tracer tracer;
  private final SessionMap sessions;

  GetSessionHistory(Tracer tracer, SessionMap sessions) {
    this.tracer = Require.nonNull("Tracer", tracer);
    this.sessions = Require.nonNull("Session map", sessions);
  }

  @Override
  public HttpResponse execute(HttpRequest req) {
    try (Span span = newSpanAsChildOf(tracer, req, "sessions.get_session_history")) {
      HTTP_REQUEST.accept(span, req);

      SessionHistoryFilters filters;
      try {
        filters = SessionHistoryFilters.fromRequest(req);
      } catch (IllegalArgumentException e) {
        return new HttpResponse().setStatus(400).setContent(asJson(errorPayload(e.getMessage())));
      }

      span.setAttribute("session.history.hasSessionId", filters.getSessionId().isPresent());
      filters.getCloseReason().ifPresent(reason -> span.setAttribute("session.history.reason", reason));
      filters
          .getStartedAfter()
          .map(Instant::toString)
          .ifPresent(start -> span.setAttribute("session.history.startedAfter", start));
      filters
          .getEndedAfter()
          .map(Instant::toString)
          .ifPresent(end -> span.setAttribute("session.history.endedAfter", end));

      List<SessionMetadata> value = sessions.getSessionHistory(filters);
      return new HttpResponse().setContent(asJson(ImmutableMap.of("value", value)));
    }
  }

  private ImmutableMap<String, Object> errorPayload(String message) {
    return ImmutableMap.of(
        "value",
        ImmutableMap.of(
            "error", "invalid argument", "message", message, "stacktrace", ""),
        "status",
        13);
  }
}

