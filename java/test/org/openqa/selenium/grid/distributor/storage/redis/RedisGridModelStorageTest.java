//
//

package org.openqa.selenium.grid.distributor.storage.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.grid.data.Availability;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.Slot;
import org.openqa.selenium.grid.data.SlotId;
import org.openqa.selenium.grid.distributor.storage.GridModelStorage;
import org.openqa.selenium.platform.Platform;
import redis.embedded.RedisServer;

class RedisGridModelStorageTest {

  private RedisServer redisServer;
  private GridModelStorage storage;
  private NodeStatus nodeStatus;
  private NodeId nodeId;

  @BeforeEach
  void setUp() throws Exception {
    redisServer = new RedisServer(6379);
    redisServer.start();
    
    storage = new RedisGridModelStorage(URI.create("redis://localhost:6379"));
    nodeId = new NodeId(UUID.randomUUID());
    
    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");
    Slot slot = new Slot(new SlotId(nodeId, UUID.randomUUID()), caps, Instant.now(), null);
    
    nodeStatus = new NodeStatus(
        nodeId,
        URI.create("http://localhost:4444"),
        1,
        Set.of(slot),
        Availability.UP,
        Duration.ofSeconds(30),
        Duration.ofMinutes(5),
        "4.0.0",
        Platform.getCurrent().family().toString());
  }

  @AfterEach
  void tearDown() throws Exception {
    if (redisServer != null) {
      redisServer.stop();
    }
  }

  @Test
  void shouldAddNode() {
    storage.addNode(nodeStatus);
    
    Set<NodeStatus> nodes = storage.getAllNodes();
    assertThat(nodes).hasSize(1);
    assertThat(nodes.iterator().next().getNodeId()).isEqualTo(nodeId);
  }

  @Test
  void shouldRemoveNode() {
    storage.addNode(nodeStatus);
    storage.removeNode(nodeId);
    
    Set<NodeStatus> nodes = storage.getAllNodes();
    assertThat(nodes).isEmpty();
  }

  @Test
  void shouldUpdateNode() {
    storage.addNode(nodeStatus);
    
    NodeStatus updatedStatus = new NodeStatus(
        nodeId,
        URI.create("http://localhost:4444"),
        2,
        nodeStatus.getSlots(),
        Availability.DOWN,
        Duration.ofSeconds(30),
        Duration.ofMinutes(5),
        "4.0.0",
        Platform.getCurrent().family().toString());
    
    storage.updateNode(updatedStatus);
    
    Set<NodeStatus> nodes = storage.getAllNodes();
    assertThat(nodes).hasSize(1);
    assertThat(nodes.iterator().next().getMaxSessionCount()).isEqualTo(2);
    assertThat(nodes.iterator().next().getAvailability()).isEqualTo(Availability.DOWN);
  }

  @Test
  void shouldGetAllNodes() {
    NodeId nodeId2 = new NodeId(UUID.randomUUID());
    Capabilities caps = new ImmutableCapabilities("browserName", "firefox");
    Slot slot2 = new Slot(new SlotId(nodeId2, UUID.randomUUID()), caps, Instant.now(), null);
    
    NodeStatus nodeStatus2 = new NodeStatus(
        nodeId2,
        URI.create("http://localhost:4445"),
        1,
        Set.of(slot2),
        Availability.UP,
        Duration.ofSeconds(30),
        Duration.ofMinutes(5),
        "4.0.0",
        Platform.getCurrent().family().toString());
    
    storage.addNode(nodeStatus);
    storage.addNode(nodeStatus2);
    
    Set<NodeStatus> nodes = storage.getAllNodes();
    assertThat(nodes).hasSize(2);
  }

  @Test
  void shouldSetAndGetPurgeTime() {
    Instant purgeTime = Instant.now();
    storage.setPurgeTime(nodeId, purgeTime);
    
    Instant retrievedTime = storage.getPurgeTime(nodeId);
    assertThat(retrievedTime).isEqualTo(purgeTime);
  }

  @Test
  void shouldRemovePurgeTime() {
    Instant purgeTime = Instant.now();
    storage.setPurgeTime(nodeId, purgeTime);
    storage.removePurgeTime(nodeId);
    
    Instant retrievedTime = storage.getPurgeTime(nodeId);
    assertThat(retrievedTime).isNull();
  }

  @Test
  void shouldSetAndGetHealthCount() {
    storage.setHealthCount(nodeId, 5);
    
    int healthCount = storage.getHealthCount(nodeId);
    assertThat(healthCount).isEqualTo(5);
  }

  @Test
  void shouldReturnZeroForUnknownHealthCount() {
    int healthCount = storage.getHealthCount(nodeId);
    assertThat(healthCount).isEqualTo(0);
  }

  @Test
  void shouldRemoveHealthCount() {
    storage.setHealthCount(nodeId, 5);
    storage.removeHealthCount(nodeId);
    
    int healthCount = storage.getHealthCount(nodeId);
    assertThat(healthCount).isEqualTo(0);
  }

  @Test
  void shouldBeReady() {
    assertThat(storage.isReady()).isTrue();
  }

  @Test
  void shouldThrowExceptionForNullNode() {
    assertThatThrownBy(() -> storage.addNode(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Node");
  }

  @Test
  void shouldThrowExceptionForNullNodeId() {
    assertThatThrownBy(() -> storage.removeNode(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Node ID");
  }

  @Test
  void shouldThrowExceptionForNullPurgeTime() {
    assertThatThrownBy(() -> storage.setPurgeTime(nodeId, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Time");
  }

  @Test
  void shouldHandleRedisConnectionFailure() {
    try {
      redisServer.stop();
      assertThat(storage.isReady()).isFalse();
    } catch (Exception e) {
    }
  }
}
