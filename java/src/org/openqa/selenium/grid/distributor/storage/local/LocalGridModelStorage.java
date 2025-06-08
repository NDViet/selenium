//
//

package org.openqa.selenium.grid.distributor.storage.local;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.distributor.storage.GridModelStorage;
import org.openqa.selenium.internal.Require;

public class LocalGridModelStorage implements GridModelStorage {

  private final Set<NodeStatus> nodes = Collections.newSetFromMap(new ConcurrentHashMap<>());
  private final Map<NodeId, Instant> nodePurgeTimes = new ConcurrentHashMap<>();
  private final Map<NodeId, Integer> nodeHealthCount = new ConcurrentHashMap<>();

  @Override
  public void addNode(NodeStatus node) {
    Require.nonNull("Node", node);
    nodes.add(node);
  }

  @Override
  public void removeNode(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    nodes.removeIf(node -> nodeId.equals(node.getNodeId()));
  }

  @Override
  public void updateNode(NodeStatus node) {
    Require.nonNull("Node", node);
    removeNode(node.getNodeId());
    addNode(node);
  }

  @Override
  public Set<NodeStatus> getAllNodes() {
    return Set.copyOf(nodes);
  }

  @Override
  public void setPurgeTime(NodeId nodeId, Instant time) {
    Require.nonNull("Node ID", nodeId);
    Require.nonNull("Time", time);
    nodePurgeTimes.put(nodeId, time);
  }

  @Override
  public Instant getPurgeTime(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    return nodePurgeTimes.get(nodeId);
  }

  @Override
  public void removePurgeTime(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    nodePurgeTimes.remove(nodeId);
  }

  @Override
  public void setHealthCount(NodeId nodeId, int count) {
    Require.nonNull("Node ID", nodeId);
    nodeHealthCount.put(nodeId, count);
  }

  @Override
  public int getHealthCount(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    return nodeHealthCount.getOrDefault(nodeId, 0);
  }

  @Override
  public void removeHealthCount(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    nodeHealthCount.remove(nodeId);
  }

  @Override
  public boolean isReady() {
    return true;
  }
}
