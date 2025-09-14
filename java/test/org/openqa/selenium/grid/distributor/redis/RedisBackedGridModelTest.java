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
import static org.openqa.selenium.grid.data.Availability.DOWN;
import static org.openqa.selenium.grid.data.Availability.UP;

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
import org.openqa.selenium.grid.data.Slot;
import org.openqa.selenium.grid.data.SlotId;

class RedisBackedGridModelTest {

  private RedisBackedGridModel model;
  private EventBus bus;
  private NodeId nodeId;
  private URI nodeUri;

  @BeforeEach
  void setUp() {
    bus = new GuavaEventBus();
    URI redisUri = URI.create("redis://localhost:6379");
    model = new RedisBackedGridModel(bus, redisUri);

    // Clean Redis state before each test
    try {
      model.connection.getConnection().sync().flushdb();
    } catch (Exception e) {
      // Ignore if Redis is not available
    }

    nodeId = new NodeId(UUID.randomUUID());
    nodeUri = URI.create("http://localhost:5555");
  }

  @Test
  void shouldAddNodeToRedis() {
    NodeStatus node = createNodeStatus(nodeId, nodeUri, UP);

    model.add(node);

    Set<NodeStatus> snapshot = model.getSnapshot();
    assertThat(snapshot).hasSize(1);
    assertThat(snapshot.iterator().next().getNodeId()).isEqualTo(nodeId);
  }

  @Test
  void shouldUpdateNodeAvailability() {
    NodeStatus node = createNodeStatus(nodeId, nodeUri, UP);
    model.add(node);

    model.setAvailability(nodeId, DOWN);

    Set<NodeStatus> snapshot = model.getSnapshot();
    NodeStatus updated = snapshot.iterator().next();
    assertThat(updated.getAvailability()).isEqualTo(DOWN);
  }

  @Test
  void shouldReserveSlot() {
    NodeStatus node = createNodeStatus(nodeId, nodeUri, UP);
    model.add(node);
    model.setAvailability(nodeId, UP);

    // Use the actual slot ID from the node
    SlotId slotId = node.getSlots().iterator().next().getId();
    boolean reserved = model.reserve(slotId);

    assertThat(reserved).isTrue();
  }

  @Test
  void shouldRemoveNode() {
    NodeStatus node = createNodeStatus(nodeId, nodeUri, UP);
    model.add(node);

    model.remove(nodeId);

    Set<NodeStatus> snapshot = model.getSnapshot();
    assertThat(snapshot).isEmpty();
  }

  private NodeStatus createNodeStatus(
      NodeId nodeId, URI uri, org.openqa.selenium.grid.data.Availability availability) {
    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");
    Slot slot = new Slot(new SlotId(nodeId, UUID.randomUUID()), caps, Instant.now(), null);

    return new NodeStatus(
        nodeId,
        uri,
        1,
        Set.of(slot),
        availability,
        Duration.ofSeconds(60),
        Duration.ofSeconds(300),
        "4.0.0",
        Map.of("os.name", "linux"));
  }
}
