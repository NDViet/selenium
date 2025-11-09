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

package org.openqa.selenium.grid.data;

import java.util.Objects;
import java.util.function.Consumer;
import org.openqa.selenium.events.Event;
import org.openqa.selenium.events.EventListener;
import org.openqa.selenium.events.EventName;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.SessionId;

public class SessionClosedEvent extends Event {

  public static final String DEFAULT_REASON = "session-closed-event";

  private static final EventName SESSION_CLOSED = new EventName("session-closed");

  public SessionClosedEvent(SessionId id) {
    this(id, DEFAULT_REASON);
  }

  public SessionClosedEvent(SessionId id, String reason) {
    super(SESSION_CLOSED, new Data(id, reason));
  }

  public static EventListener<Data> listener(Consumer<Data> handler) {
    Require.nonNull("Handler", handler);

    return new EventListener<>(SESSION_CLOSED, Data.class, handler);
  }

  public static class Data {
    private final SessionId sessionId;
    private final String reason;

    private Data(SessionId sessionId, String reason) {
      this.sessionId = Require.nonNull("Session id", sessionId);
      this.reason = Objects.requireNonNullElse(reason, DEFAULT_REASON);
    }

    public SessionId getSessionId() {
      return sessionId;
    }

    public String getReason() {
      return reason;
    }
  }
}
