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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.data.Availability;
import org.openqa.selenium.grid.data.NodeDrainStarted;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeRemovedEvent;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.data.SessionClosedEvent;
import org.openqa.selenium.grid.data.Slot;
import org.openqa.selenium.grid.data.SlotId;
import org.openqa.selenium.grid.distributor.GridModel;
import org.openqa.selenium.grid.distributor.config.DistributorOptions;
import org.openqa.selenium.grid.server.EventBusOptions;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.redis.GridRedisClient;
import org.openqa.selenium.remote.SessionId;

public class RedisBackedGridModel extends GridModel {

  private static final SessionId RESERVED = new SessionId("reserved");
  private static final Json JSON = new Json();

  // Redis key patterns for distributor data
  private static final String NODE_KEY_PREFIX = "distributor:node:";
  private static final String NODE_STATUS_KEY = ":status";
  private static final String NODE_SLOTS_KEY = ":slots";
  private static final String NODE_AVAILABILITY_SET = "distributor:availability:";
  private static final String SLOT_RESERVATION_KEY = "distributor:slot:";
  private static final String NODE_HEALTH_KEY = "distributor:health:";

  final GridRedisClient connection;
  private final EventBus events;

  public RedisBackedGridModel(EventBus events, URI redisUri) {
    this.events = Require.nonNull("Event bus", events);
    this.connection = new GridRedisClient(redisUri);

    this.events.addListener(NodeDrainStarted.listener(nodeId -> setAvailability(nodeId, DRAINING)));
    this.events.addListener(SessionClosedEvent.listener(this::release));
  }

  public static RedisBackedGridModel create(Config config) {
    EventBus bus = new EventBusOptions(config).getEventBus();
    URI redisUri = new DistributorOptions(config).getRedisUri();
    return new RedisBackedGridModel(bus, redisUri);
  }

  @Override
  public void add(NodeStatus node) {
    Require.nonNull("Node", node);

    String nodeKey = nodeKey(node.getNodeId());
    String statusKey = nodeKey + NODE_STATUS_KEY;
    String slotsKey = nodeKey + NODE_SLOTS_KEY;

    // Store node status and slots as JSON
    Map<String, String> nodeData =
        ImmutableMap.of(
            statusKey, JSON.toJson(node),
            slotsKey, JSON.toJson(node.getSlots()));

    connection.mset(nodeData);

    // Add to availability set
    String availabilityKey = NODE_AVAILABILITY_SET + DOWN.name();
    connection.getConnection().sync().sadd(availabilityKey, node.getNodeId().toString());
  }

  @Override
  public void refresh(NodeStatus status) {
    Require.nonNull("Node status", status);

    String nodeKey = nodeKey(status.getNodeId());
    String statusKey = nodeKey + NODE_STATUS_KEY;
    String existingJson = connection.get(statusKey);

    if (existingJson != null) {
      NodeStatus existing = JSON.toType(existingJson, NodeStatus.class);
      if (existing.getAvailability() == DOWN) {
        updateNodeStatus(rewrite(status, DOWN));
      } else {
        updateNodeStatus(status);
      }
    }
  }

  @Override
  public void touch(NodeStatus nodeStatus) {
    Require.nonNull("Node status", nodeStatus);

    String nodeKey = nodeKey(nodeStatus.getNodeId());
    String statusKey = nodeKey + NODE_STATUS_KEY;
    String existingJson = connection.get(statusKey);

    if (existingJson != null) {
      NodeStatus current = JSON.toType(existingJson, NodeStatus.class);
      if (current.getAvailability() != nodeStatus.getAvailability()
          && nodeStatus.getAvailability() == UP) {
        // Remove from old availability set
        String oldAvailabilityKey = NODE_AVAILABILITY_SET + current.getAvailability().name();
        connection
            .getConnection()
            .sync()
            .srem(oldAvailabilityKey, nodeStatus.getNodeId().toString());

        // Add to new availability set
        String newAvailabilityKey = NODE_AVAILABILITY_SET + UP.name();
        connection
            .getConnection()
            .sync()
            .sadd(newAvailabilityKey, nodeStatus.getNodeId().toString());

        updateNodeStatus(nodeStatus);
      }
    }
  }

  @Override
  public void remove(NodeId id) {
    Require.nonNull("Node ID", id);

    String nodeKey = nodeKey(id);
    String statusKey = nodeKey + NODE_STATUS_KEY;
    String slotsKey = nodeKey + NODE_SLOTS_KEY;
    String healthKey = NODE_HEALTH_KEY + id;

    // Get current status to remove from availability set
    String existingJson = connection.get(statusKey);
    if (existingJson != null) {
      NodeStatus node = JSON.toType(existingJson, NodeStatus.class);
      String availabilityKey = NODE_AVAILABILITY_SET + node.getAvailability().name();
      connection.getConnection().sync().srem(availabilityKey, id.toString());
    }

    // Remove all node data
    connection.del(statusKey, slotsKey, healthKey);
  }

  @Override
  public void purgeDeadNodes() {
    // Get all DOWN nodes
    String downAvailabilityKey = NODE_AVAILABILITY_SET + DOWN.name();
    Set<String> downNodeIds = connection.getConnection().sync().smembers(downAvailabilityKey);

    for (String nodeIdStr : downNodeIds) {
      NodeId nodeId = new NodeId(UUID.fromString(nodeIdStr));
      String healthKey = NODE_HEALTH_KEY + nodeId;
      String healthCount = connection.get(healthKey);

      // Simple purge logic - remove nodes that have been down for too long
      if (healthCount != null && Integer.parseInt(healthCount) > 4) {
        String statusKey = nodeKey(nodeId) + NODE_STATUS_KEY;
        String statusJson = connection.get(statusKey);
        if (statusJson != null) {
          NodeStatus node = JSON.toType(statusJson, NodeStatus.class);
          events.fire(new NodeRemovedEvent(node));
          remove(nodeId);
        }
      }
    }
  }

  @Override
  public void setAvailability(NodeId id, Availability availability) {
    Require.nonNull("Node ID", id);
    Require.nonNull("Availability", availability);

    String statusKey = nodeKey(id) + NODE_STATUS_KEY;
    String existingJson = connection.get(statusKey);

    if (existingJson != null) {
      NodeStatus node = JSON.toType(existingJson, NodeStatus.class);
      if (!availability.equals(node.getAvailability())) {
        // Remove from old availability set
        String oldAvailabilityKey = NODE_AVAILABILITY_SET + node.getAvailability().name();
        connection.getConnection().sync().srem(oldAvailabilityKey, id.toString());

        // Add to new availability set
        String newAvailabilityKey = NODE_AVAILABILITY_SET + availability.name();
        connection.getConnection().sync().sadd(newAvailabilityKey, id.toString());

        // Update node status
        NodeStatus updated = rewrite(node, availability);
        updateNodeStatus(updated);
      }
    }
  }

  @Override
  public boolean reserve(SlotId slotId) {
    String statusKey = nodeKey(slotId.getOwningNodeId()) + NODE_STATUS_KEY;
    String statusJson = connection.get(statusKey);

    if (statusJson == null) {
      return false;
    }

    NodeStatus node = JSON.toType(statusJson, NodeStatus.class);
    if (!UP.equals(node.getAvailability())) {
      return false;
    }

    Optional<Slot> maybeSlot =
        node.getSlots().stream().filter(slot -> slotId.equals(slot.getId())).findFirst();

    if (!maybeSlot.isPresent()) {
      return false;
    }

    Slot slot = maybeSlot.get();
    // Check if slot is already occupied
    if (slot.getSession() != null) {
      return false;
    }

    // Atomic reservation using Redis SETNX
    String reservationKey =
        SLOT_RESERVATION_KEY + slotId.getOwningNodeId() + ":" + slotId.getSlotId();
    
    String result = connection.getConnection().sync()
        .set(reservationKey, RESERVED.toString(), io.lettuce.core.SetArgs.Builder.nx());
    
    if (!"OK".equals(result)) {
      return false; // Already reserved by another process
    }

    // Update node status with reserved slot
    reserve(node, slot);
    return true;
  }

  @Override
  public Set<NodeStatus> getSnapshot() {
    // Get all node keys
    List<String> nodeKeys = connection.getKeysByPattern(NODE_KEY_PREFIX + "*" + NODE_STATUS_KEY);

    Set<NodeStatus> nodes = new HashSet<>();
    for (String key : nodeKeys) {
      String statusJson = connection.get(key);
      if (statusJson != null) {
        NodeStatus node = JSON.toType(statusJson, NodeStatus.class);
        nodes.add(node);
      }
    }

    return nodes;
  }

  @Override
  public void release(SessionId id) {
    if (id == null) {
      return;
    }

    // Find slot by session ID pattern
    List<String> reservationKeys = connection.getKeysByPattern(SLOT_RESERVATION_KEY + "*");

    for (String key : reservationKeys) {
      String sessionIdStr = connection.get(key);
      if (id.toString().equals(sessionIdStr)) {
        // Remove reservation
        connection.del(key);

        // Extract slot ID from key and find matching slot
        String slotIdStr = key.replace(SLOT_RESERVATION_KEY, "");
        // Parse nodeId and UUID from slotIdStr format: "nodeId:uuid"
        String[] parts = slotIdStr.split(":", 2);
        if (parts.length != 2) continue;

        NodeId nodeId = new NodeId(UUID.fromString(parts[0]));
        UUID slotUuid = UUID.fromString(parts[1]);
        SlotId slotId = new SlotId(nodeId, slotUuid);

        String statusKey = nodeKey(slotId.getOwningNodeId()) + NODE_STATUS_KEY;
        String statusJson = connection.get(statusKey);
        if (statusJson != null) {
          NodeStatus node = JSON.toType(statusJson, NodeStatus.class);
          Optional<Slot> slotOpt =
              node.getSlots().stream().filter(slot -> slotId.equals(slot.getId())).findFirst();

          if (slotOpt.isPresent()) {
            Slot slot = slotOpt.get();
            Slot released =
                new Slot(slot.getId(), slot.getStereotype(), slot.getLastStarted(), null);
            amend(node.getAvailability(), node, released);
          }
        }
        return;
      }
    }
  }

  @Override
  public void setSession(SlotId slotId, Session session) {
    Require.nonNull("Slot ID", slotId);

    String statusKey = nodeKey(slotId.getOwningNodeId()) + NODE_STATUS_KEY;
    String statusJson = connection.get(statusKey);

    if (statusJson == null) {
      return;
    }

    NodeStatus node = JSON.toType(statusJson, NodeStatus.class);
    Optional<Slot> maybeSlot =
        node.getSlots().stream().filter(slot -> slotId.equals(slot.getId())).findFirst();

    if (!maybeSlot.isPresent()) {
      return;
    }

    Slot slot = maybeSlot.get();
    
    // Update slot reservation with actual session or clear it
    String reservationKey =
        SLOT_RESERVATION_KEY + slotId.getOwningNodeId() + ":" + slotId.getSlotId();
    
    if (session != null) {
      // Set actual session ID
      connection.getConnection().sync().set(reservationKey, session.getId().toString());
      
      // Update slot with session
      Slot updated = new Slot(
          slot.getId(),
          slot.getStereotype(),
          session.getStartTime(),
          session);
      amend(node.getAvailability(), node, updated);
    } else {
      // Clear reservation and session
      connection.del(reservationKey);
      
      // Update slot without session
      Slot updated = new Slot(
          slot.getId(),
          slot.getStereotype(),
          slot.getLastStarted(),
          null);
      amend(node.getAvailability(), node, updated);
    }
  }

  @Override
  public void updateHealthCheckCount(NodeId id, Availability availability) {
    String healthKey = NODE_HEALTH_KEY + id;

    if (availability == DOWN) {
      // Increment health check failure count
      connection.getConnection().sync().incr(healthKey);
    } else if (availability == UP) {
      // Reset health check count on successful check
      connection.getConnection().sync().set(healthKey, "0");
    }
  }

  private NodeStatus rewrite(NodeStatus status, Availability availability) {
    return new NodeStatus(
        status.getNodeId(),
        status.getExternalUri(),
        status.getMaxSessionCount(),
        status.getSlots(),
        availability,
        status.getHeartbeatPeriod(),
        status.getSessionTimeout(),
        status.getVersion(),
        status.getOsInfo());
  }

  private void reserve(NodeStatus status, Slot slot) {
    Instant now = Instant.now();
    Slot reserved =
        new Slot(
            slot.getId(),
            slot.getStereotype(),
            now,
            new Session(
                RESERVED,
                status.getExternalUri(),
                slot.getStereotype(),
                slot.getStereotype(),
                now));
    amend(UP, status, reserved);
  }

  private void updateNodeStatus(NodeStatus node) {
    String nodeKey = nodeKey(node.getNodeId());
    String statusKey = nodeKey + NODE_STATUS_KEY;
    String slotsKey = nodeKey + NODE_SLOTS_KEY;

    Map<String, String> nodeData =
        ImmutableMap.of(
            statusKey, JSON.toJson(node),
            slotsKey, JSON.toJson(node.getSlots()));

    connection.mset(nodeData);
  }

  private String nodeKey(NodeId id) {
    return NODE_KEY_PREFIX + id;
  }

  private void amend(Availability availability, NodeStatus status, Slot slot) {
    Set<Slot> newSlots =
        ImmutableSet.<Slot>builder()
            .addAll(
                status.getSlots().stream()
                    .filter(s -> !s.getId().equals(slot.getId()))
                    .collect(ImmutableSet.toImmutableSet()))
            .add(slot)
            .build();

    NodeStatus updated =
        new NodeStatus(
            status.getNodeId(),
            status.getExternalUri(),
            status.getMaxSessionCount(),
            newSlots,
            availability,
            status.getHeartbeatPeriod(),
            status.getSessionTimeout(),
            status.getVersion(),
            status.getOsInfo());

    updateNodeStatus(updated);
  }
}
