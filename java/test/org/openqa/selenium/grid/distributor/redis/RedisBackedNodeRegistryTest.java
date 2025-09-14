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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

class RedisBackedNodeRegistryTest {

  private RedisBackedNodeRegistry registry;
  private RedisBackedGridModel model;
  private EventBus bus;
  private NodeId nodeId;

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

    Tracer tracer = new org.openqa.selenium.remote.tracing.empty.NullTracer();
    HttpClient.Factory clientFactory = mock(HttpClient.Factory.class);
    Duration healthcheckInterval = Duration.ofSeconds(60);

    registry = new RedisBackedNodeRegistry(tracer, bus, clientFactory, healthcheckInterval, model);
    nodeId = new NodeId(UUID.randomUUID());
  }

  @Test
  void shouldAddNode() {
    Node node = createMockNode();

    registry.add(node);

    assertThat(registry.getNode(nodeId)).isEqualTo(node);
    assertThat(registry.getUpNodeCount()).isEqualTo(1);
  }

  @Test
  void shouldRemoveNode() {
    Node node = createMockNode();
    registry.add(node);

    registry.remove(nodeId);

    assertThat(registry.getNode(nodeId)).isNull();
    assertThat(registry.getUpNodeCount()).isEqualTo(0);
  }

  @Test
  void shouldGetAvailableNodes() {
    Node node = createMockNode();
    registry.add(node);

    Set<NodeStatus> availableNodes = registry.getAvailableNodes();

    assertThat(availableNodes).hasSize(1);
  }

  @Test
  void shouldReserveSlot() {
    Node node = createMockNode();
    registry.add(node);

    // Set node availability to UP for reservation to work
    model.setAvailability(nodeId, UP);

    // Use the actual slot ID from the node
    SlotId slotId = node.getStatus().getSlots().iterator().next().getId();
    boolean reserved = registry.reserve(slotId);

    assertThat(reserved).isTrue();
  }

  private Node createMockNode() {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(nodeId);

    NodeStatus status = createNodeStatus();
    when(node.getStatus()).thenReturn(status);

    return node;
  }

  private NodeStatus createNodeStatus() {
    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");
    Slot slot = new Slot(new SlotId(nodeId, UUID.randomUUID()), caps, Instant.now(), null);

    return new NodeStatus(
        nodeId,
        URI.create("http://localhost:5555"),
        1,
        Set.of(slot),
        UP,
        Duration.ofSeconds(60),
        Duration.ofSeconds(300),
        "4.0.0",
        Map.of("os.name", "linux"));
  }
}
