//
//

package org.openqa.selenium.grid.distributor.storage.redis;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.distributor.storage.GridModelStorage;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.redis.GridRedisClient;

public class RedisGridModelStorage implements GridModelStorage {

  private static final Logger LOG = Logger.getLogger(RedisGridModelStorage.class.getName());
  private static final String NODES_KEY_PREFIX = "gridmodel:nodes:";
  private static final String PURGE_KEY_PREFIX = "gridmodel:purge:";
  private static final String HEALTH_KEY_PREFIX = "gridmodel:health:";
  private static final String NODES_SET_KEY = "gridmodel:nodes:set";

  private final GridRedisClient connection;
  private final Json json;

  public RedisGridModelStorage(URI serverUri) {
    this.connection = new GridRedisClient(serverUri);
    this.json = new Json();
  }

  @Override
  public void addNode(NodeStatus node) {
    Require.nonNull("Node", node);
    String nodeKey = NODES_KEY_PREFIX + node.getNodeId().toString();
    String nodeJson = json.toJson(node);
    
    connection.set(nodeKey, nodeJson);
    connection.sadd(NODES_SET_KEY, node.getNodeId().toString());
  }

  @Override
  public void removeNode(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    String nodeKey = NODES_KEY_PREFIX + nodeId.toString();
    
    connection.del(nodeKey);
    connection.srem(NODES_SET_KEY, nodeId.toString());
    removePurgeTime(nodeId);
    removeHealthCount(nodeId);
  }

  @Override
  public void updateNode(NodeStatus node) {
    Require.nonNull("Node", node);
    String nodeKey = NODES_KEY_PREFIX + node.getNodeId().toString();
    String nodeJson = json.toJson(node);
    
    connection.set(nodeKey, nodeJson);
    connection.sadd(NODES_SET_KEY, node.getNodeId().toString());
  }

  @Override
  public Set<NodeStatus> getAllNodes() {
    Set<String> nodeIds = connection.smembers(NODES_SET_KEY);
    Set<NodeStatus> nodes = new HashSet<>();
    
    for (String nodeIdStr : nodeIds) {
      String nodeKey = NODES_KEY_PREFIX + nodeIdStr;
      String nodeJson = connection.get(nodeKey);
      if (nodeJson != null) {
        try {
          NodeStatus node = json.toType(nodeJson, NodeStatus.class);
          nodes.add(node);
        } catch (Exception e) {
          LOG.warning("Failed to deserialize node " + nodeIdStr + ": " + e.getMessage());
          connection.srem(NODES_SET_KEY, nodeIdStr);
        }
      } else {
        connection.srem(NODES_SET_KEY, nodeIdStr);
      }
    }
    
    return nodes;
  }

  @Override
  public void setPurgeTime(NodeId nodeId, Instant time) {
    Require.nonNull("Node ID", nodeId);
    Require.nonNull("Time", time);
    String purgeKey = PURGE_KEY_PREFIX + nodeId.toString();
    connection.set(purgeKey, time.toString());
  }

  @Override
  public Instant getPurgeTime(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    String purgeKey = PURGE_KEY_PREFIX + nodeId.toString();
    String timeStr = connection.get(purgeKey);
    return timeStr != null ? Instant.parse(timeStr) : null;
  }

  @Override
  public void removePurgeTime(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    String purgeKey = PURGE_KEY_PREFIX + nodeId.toString();
    connection.del(purgeKey);
  }

  @Override
  public void setHealthCount(NodeId nodeId, int count) {
    Require.nonNull("Node ID", nodeId);
    String healthKey = HEALTH_KEY_PREFIX + nodeId.toString();
    connection.set(healthKey, String.valueOf(count));
  }

  @Override
  public int getHealthCount(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    String healthKey = HEALTH_KEY_PREFIX + nodeId.toString();
    String countStr = connection.get(healthKey);
    return countStr != null ? Integer.parseInt(countStr) : 0;
  }

  @Override
  public void removeHealthCount(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    String healthKey = HEALTH_KEY_PREFIX + nodeId.toString();
    connection.del(healthKey);
  }

  @Override
  public boolean isReady() {
    try {
      connection.ping();
      return true;
    } catch (Exception e) {
      LOG.warning("Redis connection not ready: " + e.getMessage());
      return false;
    }
  }
}
