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
import org.junit.jupiter.api.AfterEach;
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

class RedisIntegrationTest {

  private RedisBackedGridModel model;
  private EventBus bus;
  private NodeId nodeId;
  private URI redisUri;

  @BeforeEach
  void setUp() {
    bus = new GuavaEventBus();
    redisUri = URI.create("redis://localhost:6379");
    model = new RedisBackedGridModel(bus, redisUri);
    nodeId = new NodeId(UUID.randomUUID());

    // Clean up Redis state before test
    cleanupRedis();
  }

  @AfterEach
  void tearDown() {
    // Clean up Redis state after test to ensure clean state for next run
    cleanupRedis();

    // Close Redis connection
    try {
      model.connection.close();
    } catch (Exception e) {
      // Ignore close errors
    }
  }

  private void cleanupRedis() {
    try {
      model.connection.getConnection().sync().flushdb();
    } catch (Exception e) {
      // Ignore cleanup errors
    }
  }

  @Test
  void shouldConnectToRedis() {
    assertThat(model.connection.isOpen()).isTrue();
  }

  @Test
  void shouldAddAndRetrieveNode() {
    NodeStatus node = createNodeStatus(nodeId, URI.create("http://localhost:5555"), UP);

    model.add(node);

    Set<NodeStatus> snapshot = model.getSnapshot();
    assertThat(snapshot).hasSize(1);
    assertThat(snapshot.iterator().next().getNodeId()).isEqualTo(nodeId);
  }

  @Test
  void shouldUpdateNodeAvailability() {
    NodeStatus node = createNodeStatus(nodeId, URI.create("http://localhost:5555"), UP);
    model.add(node);

    model.setAvailability(nodeId, DOWN);

    Set<NodeStatus> snapshot = model.getSnapshot();
    NodeStatus updated = snapshot.iterator().next();
    assertThat(updated.getAvailability()).isEqualTo(DOWN);
  }

  @Test
  void shouldReserveSlot() {
    NodeStatus node = createNodeStatus(nodeId, URI.create("http://localhost:5555"), UP);
    model.add(node);
    model.setAvailability(nodeId, UP);

    SlotId slotId = node.getSlots().iterator().next().getId();
    boolean reserved = model.reserve(slotId);

    assertThat(reserved).isTrue();
  }

  @Test
  void shouldRemoveNode() {
    NodeStatus node = createNodeStatus(nodeId, URI.create("http://localhost:5555"), UP);
    model.add(node);

    model.remove(nodeId);

    Set<NodeStatus> snapshot = model.getSnapshot();
    assertThat(snapshot).isEmpty();
  }

  @Test
  void shouldTrackHealthCheckFailures() {
    model.updateHealthCheckCount(nodeId, DOWN);
    model.updateHealthCheckCount(nodeId, DOWN);

    // Health count should be tracked in Redis
    String healthKey = "distributor:health:" + nodeId;
    String count = model.connection.get(healthKey);
    assertThat(count).isEqualTo("2");
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
