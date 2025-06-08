//
//

package org.openqa.selenium.grid.data;

import java.time.Instant;
import java.util.Objects;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.SessionId;

public class SessionHistoryEntry {
  private final SessionId sessionId;
  private final Instant startTime;
  private final Instant stopTime;
  private final SessionStatus status;

  public SessionHistoryEntry(SessionId sessionId, Instant startTime, Instant stopTime, SessionStatus status) {
    this.sessionId = Require.nonNull("Session ID", sessionId);
    this.startTime = Require.nonNull("Start time", startTime);
    this.stopTime = stopTime; // Can be null for ongoing sessions
    this.status = Require.nonNull("Session status", status);
  }

  public SessionHistoryEntry(SessionId sessionId, Instant startTime, Instant stopTime) {
    this(sessionId, startTime, stopTime, SessionStatus.SUCCESS);
  }

  public SessionId getSessionId() {
    return sessionId;
  }

  public Instant getStartTime() {
    return startTime;
  }

  public Instant getStopTime() {
    return stopTime;
  }

  public SessionStatus getStatus() {
    return status;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SessionHistoryEntry)) return false;
    SessionHistoryEntry that = (SessionHistoryEntry) o;
    return Objects.equals(sessionId, that.sessionId) &&
           Objects.equals(startTime, that.startTime) &&
           Objects.equals(stopTime, that.stopTime) &&
           Objects.equals(status, that.status);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sessionId, startTime, stopTime, status);
  }
}
