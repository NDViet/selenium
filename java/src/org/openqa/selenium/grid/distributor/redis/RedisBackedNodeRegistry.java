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

import static org.openqa.selenium.grid.data.Availability.DOWN;
import static org.openqa.selenium.grid.data.Availability.DRAINING;
import static org.openqa.selenium.grid.data.Availability.UP;

import com.google.common.collect.ImmutableSet;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.data.Availability;
import org.openqa.selenium.grid.data.DistributorStatus;
import org.openqa.selenium.grid.data.NodeAddedEvent;
import org.openqa.selenium.grid.data.NodeDrainComplete;
import org.openqa.selenium.grid.data.NodeHeartBeatEvent;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeRemovedEvent;
import org.openqa.selenium.grid.data.NodeRestartedEvent;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.NodeStatusEvent;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.data.SlotId;
import org.openqa.selenium.grid.distributor.NodeRegistry;
import org.openqa.selenium.grid.distributor.config.DistributorOptions;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.node.remote.RemoteNode;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.security.SecretOptions;
import org.openqa.selenium.grid.server.EventBusOptions;
import org.openqa.selenium.grid.server.NetworkOptions;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

public class RedisBackedNodeRegistry implements NodeRegistry {

  private static final SessionId RESERVED = new SessionId("reserved");
  private static final Json JSON = new Json();

  // Redis key patterns
  private static final String NODE_REGISTRY_KEY = "distributor:registry:";
  private static final String NODE_KEY_PREFIX = "distributor:node:";
  private static final String NODE_STATUS_KEY = ":status";

  private final Tracer tracer;
  private final EventBus bus;
  private final HttpClient.Factory clientFactory;
  private final Duration healthcheckInterval;
  private final RedisBackedGridModel model;
  private final Map<NodeId, Node> nodes = new ConcurrentHashMap<>();
  private final ReadWriteLock lock = new ReentrantReadWriteLock(/* fair */ true);
  private final Secret registrationSecret;

  public RedisBackedNodeRegistry(
      Tracer tracer,
      EventBus bus,
      HttpClient.Factory clientFactory,
      Duration healthcheckInterval,
      RedisBackedGridModel model) {
    this.tracer = Require.nonNull("Tracer", tracer);
    this.bus = Require.nonNull("Event bus", bus);
    this.clientFactory = Require.nonNull("HTTP client factory", clientFactory);
    this.healthcheckInterval = Require.nonNull("Health check interval", healthcheckInterval);
    this.model = Require.nonNull("Grid model", model);
    this.registrationSecret = getRegistrationSecret();

    // Register listeners for node events - critical for node registration
    this.bus.addListener(org.openqa.selenium.grid.data.NodeStatusEvent.listener(this::register));
    this.bus.addListener(org.openqa.selenium.grid.data.NodeStatusEvent.listener(model::refresh));
    this.bus.addListener(
        org.openqa.selenium.grid.data.NodeRestartedEvent.listener(previousNodeStatus -> remove(previousNodeStatus.getNodeId())));
    this.bus.addListener(org.openqa.selenium.grid.data.NodeRemovedEvent.listener(nodeStatus -> remove(nodeStatus.getNodeId())));
    this.bus.addListener(org.openqa.selenium.grid.data.NodeDrainComplete.listener(this::remove));
    this.bus.addListener(
        org.openqa.selenium.grid.data.NodeHeartBeatEvent.listener(
            nodeStatus -> {
              if (nodes.containsKey(nodeStatus.getNodeId())) {
                model.touch(nodeStatus);
              } else {
                register(nodeStatus);
              }
            }));
  }

  public static RedisBackedNodeRegistry create(Config config) {
    Tracer tracer = new LoggingOptions(config).getTracer();
    EventBus bus = new EventBusOptions(config).getEventBus();
    HttpClient.Factory clientFactory = new NetworkOptions(config).getHttpClientFactory(tracer);
    Duration healthcheckInterval = new DistributorOptions(config).getHealthCheckInterval();
    RedisBackedGridModel model = RedisBackedGridModel.create(config);

    return new RedisBackedNodeRegistry(tracer, bus, clientFactory, healthcheckInterval, model);
  }

  @Override
  public void register(NodeStatus status) {
    Require.nonNull("Node", status);

    Lock writeLock = lock.writeLock();
    writeLock.lock();
    try {
      if (nodes.containsKey(status.getNodeId())) {
        return;
      }

      if (status.getAvailability() != UP) {
        return;
      }

      // Create RemoteNode for the registered node
      RemoteNode remoteNode =
          new RemoteNode(
              tracer,
              clientFactory,
              status.getNodeId(),
              status.getExternalUri(),
              registrationSecret,
              status.getSessionTimeout(),
              status.getSlots().stream()
                  .map(slot -> slot.getStereotype())
                  .collect(Collectors.toSet()));

      add(remoteNode);
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void add(Node node) {
    Require.nonNull("Node", node);

    Lock writeLock = lock.writeLock();
    writeLock.lock();
    try {
      NodeStatus initialNodeStatus = node.getStatus();
      if (initialNodeStatus.getAvailability() != UP) {
        return;
      }

      // Store node in local registry
      nodes.put(node.getId(), node);
      model.add(initialNodeStatus);

      bus.fire(new NodeAddedEvent(node.getId()));
    } catch (Exception e) {
      // Log and continue
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void remove(NodeId nodeId) {
    Lock writeLock = lock.writeLock();
    writeLock.lock();
    try {
      Node node = nodes.remove(nodeId);
      if (node != null) {
        model.remove(nodeId);

        if (node instanceof RemoteNode) {
          try {
            ((RemoteNode) node).close();
          } catch (Exception e) {
            // Log warning
          }
        }
      }
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public boolean drain(NodeId nodeId) {
    Node node = nodes.get(nodeId);
    if (node == null) {
      return false;
    }

    node.drain();
    model.setAvailability(nodeId, DRAINING);
    return node.isDraining();
  }

  @Override
  public void updateNodeAvailability(URI nodeUri, NodeId id, Availability availability) {
    Require.nonNull("Node URI", nodeUri);
    Require.nonNull("Node ID", id);
    Require.nonNull("Availability", availability);
    model.setAvailability(id, availability);
    model.updateHealthCheckCount(id, availability);
  }

  @Override
  public void runHealthChecks() {
    // Simplified health check implementation
  }

  @Override
  public void refresh() {
    // Refresh implementation
  }

  @Override
  public DistributorStatus getStatus() {
    return new DistributorStatus(model.getSnapshot());
  }

  @Override
  public Set<NodeStatus> getAvailableNodes() {
    Lock readLock = lock.readLock();
    readLock.lock();
    try {
      return model.getSnapshot().stream()
          .filter(node -> UP.equals(node.getAvailability()) && node.hasCapacity())
          .collect(ImmutableSet.toImmutableSet());
    } finally {
      readLock.unlock();
    }
  }

  private String nodeRegistryKey(NodeId id) {
    return NODE_REGISTRY_KEY + id;
  }

  private String nodeStatusKey(NodeId id) {
    return NODE_KEY_PREFIX + id + NODE_STATUS_KEY;
  }

  @Override
  public Node getNode(NodeId id) {
    return nodes.get(id);
  }

  @Override
  public long getUpNodeCount() {
    return model.getSnapshot().stream().filter(node -> UP.equals(node.getAvailability())).count();
  }

  @Override
  public long getDownNodeCount() {
    return model.getSnapshot().stream().filter(node -> DOWN.equals(node.getAvailability())).count();
  }

  @Override
  public boolean isReady() {
    return bus.isReady();
  }

  @Override
  public boolean reserve(SlotId slotId) {
    Require.nonNull("Slot ID", slotId);
    Lock writeLock = lock.writeLock();
    writeLock.lock();
    try {
      return model.reserve(slotId);
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void setSession(SlotId slotId, Session session) {
    model.setSession(slotId, session);
  }

  @Override
  public int getActiveSlots() {
    return model.getSnapshot().stream()
        .flatMap(node -> node.getSlots().stream())
        .filter(slot -> slot.getSession() != null)
        .filter(slot -> !slot.getSession().getId().equals(RESERVED))
        .mapToInt(slot -> 1)
        .sum();
  }

  @Override
  public int getIdleSlots() {
    return (int)
        (model.getSnapshot().stream().flatMap(status -> status.getSlots().stream()).count()
            - getActiveSlots());
  }

  public Node getNode(URI uri) {
    Lock readLock = lock.readLock();
    readLock.lock();
    try {
      Optional<NodeStatus> nodeStatus =
          model.getSnapshot().stream()
              .filter(node -> node.getExternalUri().equals(uri))
              .findFirst();

      return nodeStatus.map(status -> nodes.get(status.getNodeId())).orElse(null);
    } finally {
      readLock.unlock();
    }
  }

  private Secret getRegistrationSecret() {
    try {
      return new SecretOptions(null).getRegistrationSecret();
    } catch (Exception e) {
      return new Secret("test-secret");
    }
  }

  @Override
  public void close() {
    nodes
        .values()
        .forEach(
            n -> {
              if (n instanceof RemoteNode) {
                try {
                  ((RemoteNode) n).close();
                } catch (Exception e) {
                  // Log warning
                }
              }
            });
    nodes.clear();
  }
}
