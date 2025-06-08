//
//

package org.openqa.selenium.grid.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.remote.http.HttpMethod.GET;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.grid.data.SessionHistoryEntry;
import org.openqa.selenium.grid.data.SessionStatus;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

class GetNodeSessionHistoryTest {

  @Test
  void shouldReturnSessionHistoryAsJson() {
    SessionId sessionId = new SessionId("test-session");
    Instant startTime = Instant.now();
    Instant stopTime = startTime.plusSeconds(60);
    SessionHistoryEntry entry = new SessionHistoryEntry(sessionId, startTime, stopTime, SessionStatus.SUCCESS);
    
    TestNode node = new TestNode(Arrays.asList(entry));
    GetNodeSessionHistory handler = new GetNodeSessionHistory(node);
    
    HttpResponse response = handler.execute(new HttpRequest(GET, "/"));
    
    assertThat(response.getStatus()).isEqualTo(200);
    String content = response.getContentString();
    assertThat(content).contains("test-session");
    assertThat(content).contains("value");
    assertThat(content).contains("\"status\":\"SUCCESS\"");
    
    Json json = new Json();
    Object responseObj = json.toType(content, Object.class);
    assertThat(responseObj).isNotNull();
  }
  
  @Test
  void shouldReturnEmptyListWhenNoHistory() {
    TestNode node = new TestNode(Arrays.asList());
    GetNodeSessionHistory handler = new GetNodeSessionHistory(node);
    
    HttpResponse response = handler.execute(new HttpRequest(GET, "/"));
    
    assertThat(response.getStatus()).isEqualTo(200);
    String content = response.getContentString();
    assertThat(content).contains("value");
    assertThat(content).contains("[]");
  }

  @Test
  void shouldIncludeSessionStatusInResponse() {
    SessionId sessionId1 = new SessionId("success-session");
    SessionId sessionId2 = new SessionId("failed-session");
    Instant startTime = Instant.now();
    Instant stopTime = startTime.plusSeconds(60);
    
    SessionHistoryEntry successEntry = new SessionHistoryEntry(sessionId1, startTime, stopTime, SessionStatus.SUCCESS);
    SessionHistoryEntry failedEntry = new SessionHistoryEntry(sessionId2, startTime, stopTime, SessionStatus.FAILED);
    
    TestNode node = new TestNode(Arrays.asList(successEntry, failedEntry));
    GetNodeSessionHistory handler = new GetNodeSessionHistory(node);
    
    HttpResponse response = handler.execute(new HttpRequest(GET, "/"));
    
    assertThat(response.getStatus()).isEqualTo(200);
    String content = response.getContentString();
    assertThat(content).contains("success-session");
    assertThat(content).contains("failed-session");
    assertThat(content).contains("\"status\":\"SUCCESS\"");
    assertThat(content).contains("\"status\":\"FAILED\"");
    
    Json json = new Json();
    Object responseObj = json.toType(content, Object.class);
    assertThat(responseObj).isNotNull();
  }
  
  private static class TestNode extends Node {
    private final List<SessionHistoryEntry> history;
    
    TestNode(List<SessionHistoryEntry> history) {
      super(null, null, null, null, null);
      this.history = history;
    }
    
    @Override
    public List<SessionHistoryEntry> getSessionHistory() {
      return history;
    }
    
    @Override
    public org.openqa.selenium.internal.Either<org.openqa.selenium.WebDriverException, org.openqa.selenium.grid.data.CreateSessionResponse> newSession(org.openqa.selenium.grid.data.CreateSessionRequest sessionRequest) {
      return null;
    }
    
    @Override
    public org.openqa.selenium.remote.http.HttpResponse executeWebDriverCommand(org.openqa.selenium.remote.http.HttpRequest req) {
      return null;
    }
    
    @Override
    public org.openqa.selenium.grid.data.Session getSession(org.openqa.selenium.remote.SessionId id) {
      return null;
    }
    
    @Override
    public org.openqa.selenium.remote.http.HttpResponse uploadFile(org.openqa.selenium.remote.http.HttpRequest req, org.openqa.selenium.remote.SessionId id) {
      return null;
    }
    
    @Override
    public org.openqa.selenium.remote.http.HttpResponse downloadFile(org.openqa.selenium.remote.http.HttpRequest req, org.openqa.selenium.remote.SessionId id) {
      return null;
    }
    
    @Override
    public void stop(org.openqa.selenium.remote.SessionId id) {
    }
    
    @Override
    public boolean isSessionOwner(org.openqa.selenium.remote.SessionId id) {
      return false;
    }
    
    @Override
    public boolean tryAcquireConnection(org.openqa.selenium.remote.SessionId id) {
      return false;
    }
    
    @Override
    public void releaseConnection(org.openqa.selenium.remote.SessionId id) {
    }
    
    @Override
    public boolean isSupporting(org.openqa.selenium.Capabilities capabilities) {
      return false;
    }
    
    @Override
    public org.openqa.selenium.grid.data.NodeStatus getStatus() {
      return null;
    }
    
    @Override
    public org.openqa.selenium.grid.node.HealthCheck getHealthCheck() {
      return null;
    }
    
    @Override
    public void drain() {
    }
  }
}
