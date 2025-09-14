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
import org.openqa.selenium.grid.data.NodeStatusEvent;
import org.openqa.selenium.grid.data.Slot;
import org.openqa.selenium.grid.data.SlotId;
import org.openqa.selenium.grid.distributor.selector.DefaultSlotSelector;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.grid.sessionmap.local.LocalSessionMap;
import org.openqa.selenium.grid.sessionqueue.NewSessionQueue;
import org.openqa.selenium.grid.sessionqueue.local.LocalNewSessionQueue;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

class RedisBackedDistributorAddNodeTest {

  private RedisBackedDistributor distributor;
  private RedisBackedNodeRegistry nodeRegistry;
  private EventBus bus;
  private NodeId nodeId;

  @BeforeEach
  void setUp() {
    bus = new GuavaEventBus();
    Tracer tracer = new org.openqa.selenium.remote.tracing.empty.NullTracer();
    HttpClient.Factory clientFactory = mock(HttpClient.Factory.class);
    SessionMap sessionMap = new LocalSessionMap(tracer, bus);
    NewSessionQueue sessionQueue =
        new LocalNewSessionQueue(
            tracer, 
            new org.openqa.selenium.grid.data.DefaultSlotMatcher(),
            Duration.ofSeconds(5), 
            Duration.ofSeconds(2), 
            Duration.ofSeconds(300),
            new Secret("test-secret"),
            3);

    URI redisUri = URI.create("redis://localhost:6379");
    RedisBackedGridModel model = new RedisBackedGridModel(bus, redisUri);
    
    // Clean Redis state before each test
    try {
      model.connection.getConnection().sync().flushdb();
    } catch (Exception e) {
      // Ignore if Redis is not available
    }
    
    nodeRegistry =
        new RedisBackedNodeRegistry(tracer, bus, clientFactory, Duration.ofSeconds(60), model);

    distributor =
        new RedisBackedDistributor(
            tracer,
            bus,
            clientFactory,
            sessionMap,
            sessionQueue,
            new DefaultSlotSelector(),
            new Secret("test-secret"),
            Duration.ofSeconds(60),
            false,
            Duration.ofSeconds(5),
            3,
            null,
            Duration.ofSeconds(30),
            nodeRegistry);

    nodeId = new NodeId(UUID.randomUUID());
  }

  @Test
  void shouldAddNodeDirectly() {
    Node node = createMockNode();

    distributor.add(node);

    assertThat(distributor.getStatus().getNodes()).hasSize(1);
    assertThat(nodeRegistry.getNode(nodeId)).isEqualTo(node);
  }

  @Test
  void shouldHandleMultipleNodes() {
    Node node1 = createMockNode();
    Node node2 = createMockNode(new NodeId(UUID.randomUUID()), URI.create("http://localhost:5556"));

    distributor.add(node1);
    distributor.add(node2);

    assertThat(distributor.getStatus().getNodes()).hasSize(2);
  }

  @Test
  void shouldDrainNode() {
    Node node = createMockNode();
    when(node.isDraining()).thenReturn(true);
    distributor.add(node);

    boolean drained = distributor.drain(nodeId);

    assertThat(drained).isTrue();
  }

  @Test
  void shouldRemoveNode() {
    Node node = createMockNode();
    distributor.add(node);
    assertThat(distributor.getStatus().getNodes()).hasSize(1);

    distributor.remove(nodeId);

    assertThat(distributor.getStatus().getNodes()).isEmpty();
  }

  @Test
  void shouldGetNodeFromURI() {
    Node node = createMockNode();
    distributor.add(node);
    
    assertThat(distributor.getStatus().getNodes()).hasSize(1);

    Node foundNode = distributor.getNodeFromURI(URI.create("http://localhost:5555"));

    assertThat(foundNode).isEqualTo(node);
  }

  private Node createMockNode() {
    return createMockNode(nodeId, URI.create("http://localhost:5555"));
  }

  private Node createMockNode(NodeId id, URI uri) {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getStatus()).thenReturn(createNodeStatus(id, uri));
    return node;
  }

  private NodeStatus createNodeStatus() {
    return createNodeStatus(nodeId, URI.create("http://localhost:5555"));
  }

  private NodeStatus createNodeStatus(NodeId id, URI uri) {
    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");
    Slot slot = new Slot(new SlotId(id, UUID.randomUUID()), caps, Instant.now(), null);

    return new NodeStatus(
        id,
        uri,
        1,
        Set.of(slot),
        UP,
        Duration.ofSeconds(60),
        Duration.ofSeconds(300),
        "4.0.0",
        Map.of("os.name", "linux"));
  }
}