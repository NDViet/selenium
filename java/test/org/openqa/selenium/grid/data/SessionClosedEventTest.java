//
//

package org.openqa.selenium.grid.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.json.JsonException;
import org.openqa.selenium.remote.SessionId;

class SessionClosedEventTest {

  @Test
  void shouldSerializeAndDeserializeWithSuccessStatus() {
    SessionId sessionId = new SessionId("test-session-123");
    SessionClosedEvent originalEvent = new SessionClosedEvent(sessionId, SessionStatus.SUCCESS);
    
    Json json = new Json();
    String serialized = json.toJson(originalEvent);
    SessionClosedEvent deserializedEvent = json.toType(serialized, SessionClosedEvent.class);
    
    assertThat(deserializedEvent).isNotNull();
    assertThat(deserializedEvent.getData(SessionId.class)).isEqualTo(sessionId);
    assertThat(deserializedEvent.getStatus()).isEqualTo(SessionStatus.SUCCESS);
  }

  @Test
  void shouldSerializeAndDeserializeWithFailedStatus() {
    SessionId sessionId = new SessionId("test-session-456");
    SessionClosedEvent originalEvent = new SessionClosedEvent(sessionId, SessionStatus.FAILED);
    
    Json json = new Json();
    String serialized = json.toJson(originalEvent);
    SessionClosedEvent deserializedEvent = json.toType(serialized, SessionClosedEvent.class);
    
    assertThat(deserializedEvent).isNotNull();
    assertThat(deserializedEvent.getData(SessionId.class)).isEqualTo(sessionId);
    assertThat(deserializedEvent.getStatus()).isEqualTo(SessionStatus.FAILED);
  }

  @Test
  void shouldUseDefaultSuccessStatusWhenNotSpecified() {
    SessionId sessionId = new SessionId("test-session-789");
    SessionClosedEvent event = new SessionClosedEvent(sessionId);
    
    assertThat(event.getStatus()).isEqualTo(SessionStatus.SUCCESS);
  }

  @Test
  void shouldHandleEventDataSerialization() {
    SessionId sessionId = new SessionId("test-session-abc");
    SessionClosedEvent event = new SessionClosedEvent(sessionId, SessionStatus.FAILED);
    
    String rawData = event.getRawData();
    assertThat(rawData).isNotEmpty();
    assertThat(rawData).contains(sessionId.toString());
  }
}
