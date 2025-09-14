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

package org.openqa.selenium.grid.distributor.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.events.local.GuavaEventBus;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.data.Slot;
import org.openqa.selenium.grid.data.SlotId;
import org.openqa.selenium.remote.SessionId;

class RedisSessionReservationTest {

  private RedisBackedGridModel model;
  private EventBus bus;
  private NodeId nodeId;
  private SlotId slotId;

  @BeforeEach
  void setUp() {
    bus = new GuavaEventBus();
    URI redisUri = URI.create("redis://localhost:6379");
    model = new RedisBackedGridModel(bus, redisUri);
    
    try {
      model.connection.getConnection().sync().flushdb();
    } catch (Exception e) {
      // Ignore if Redis is not available
    }
    
    nodeId = new NodeId(UUID.randomUUID());
    slotId = new SlotId(nodeId, UUID.randomUUID());
  }

  @Test
  void shouldReserveSlotAtomically() {
    // Add node with available slot
    NodeStatus nodeStatus = createNodeStatus();
    model.add(nodeStatus);
    model.setAvailability(nodeId, org.openqa.selenium.grid.data.Availability.UP);

    // Reserve slot
    boolean reserved = model.reserve(slotId);
    assertThat(reserved).isTrue();

    // Verify slot is reserved in Redis
    String reservationKey = "distributor:slot:" + nodeId + ":" + slotId.getSlotId();
    String reservedValue = model.connection.get(reservationKey);
    assertThat(reservedValue).isEqualTo("reserved");

    // Try to reserve same slot again - should fail
    boolean reservedAgain = model.reserve(slotId);
    assertThat(reservedAgain).isFalse();
  }

  @Test
  void shouldSetAndClearSession() {
    // Add node and reserve slot
    NodeStatus nodeStatus = createNodeStatus();
    model.add(nodeStatus);
    model.setAvailability(nodeId, org.openqa.selenium.grid.data.Availability.UP);
    model.reserve(slotId);

    // Create session
    SessionId sessionId = new SessionId(UUID.randomUUID().toString());
    Session session = new Session(
        sessionId,
        URI.create("http://localhost:5555"),
        new ImmutableCapabilities("browserName", "chrome"),
        new ImmutableCapabilities("browserName", "chrome"),
        Instant.now());

    // Set session
    model.setSession(slotId, session);

    // Verify session is stored in Redis
    String reservationKey = "distributor:slot:" + nodeId + ":" + slotId.getSlotId();
    String storedSessionId = model.connection.get(reservationKey);
    assertThat(storedSessionId).isEqualTo(sessionId.toString());

    // Verify node status reflects session
    Set<NodeStatus> snapshot = model.getSnapshot();
    NodeStatus updatedNode = snapshot.iterator().next();
    Slot updatedSlot = updatedNode.getSlots().iterator().next();
    assertThat(updatedSlot.getSession()).isNotNull();
    assertThat(updatedSlot.getSession().getId()).isEqualTo(sessionId);

    // Clear session
    model.setSession(slotId, null);

    // Verify reservation is cleared
    String clearedValue = model.connection.get(reservationKey);
    assertThat(clearedValue).isNull();

    // Verify node status reflects cleared session
    Set<NodeStatus> clearedSnapshot = model.getSnapshot();
    NodeStatus clearedNode = clearedSnapshot.iterator().next();
    Slot clearedSlot = clearedNode.getSlots().iterator().next();
    assertThat(clearedSlot.getSession()).isNull();
  }

  private NodeStatus createNodeStatus() {
    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");
    Slot slot = new Slot(slotId, caps, Instant.now(), null);

    return new NodeStatus(
        nodeId,
        URI.create("http://localhost:5555"),
        1,
        Set.of(slot),
        org.openqa.selenium.grid.data.Availability.UP,
        Duration.ofSeconds(60),
        Duration.ofSeconds(300),
        "4.0.0",
        Map.of("os.name", "linux"));
  }
}