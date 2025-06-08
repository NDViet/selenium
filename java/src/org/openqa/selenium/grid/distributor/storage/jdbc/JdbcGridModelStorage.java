//
//

package org.openqa.selenium.grid.distributor.storage.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.distributor.storage.GridModelStorage;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;

public class JdbcGridModelStorage implements GridModelStorage {

  private static final Logger LOG = Logger.getLogger(JdbcGridModelStorage.class.getName());
  private final Connection connection;
  private final Json json;

  public JdbcGridModelStorage(Connection connection) {
    this.connection = Require.nonNull("Database connection", connection);
    this.json = new Json();
    createTablesIfNotExists();
  }

  private void createTablesIfNotExists() {
    try {
      connection.createStatement().execute(
          "CREATE TABLE IF NOT EXISTS grid_nodes ("
              + "node_id VARCHAR(256) PRIMARY KEY, "
              + "node_data TEXT NOT NULL, "
              + "availability VARCHAR(50) NOT NULL)");

      connection.createStatement().execute(
          "CREATE TABLE IF NOT EXISTS grid_purge_times ("
              + "node_id VARCHAR(256) PRIMARY KEY, "
              + "purge_time TIMESTAMP NOT NULL)");

      connection.createStatement().execute(
          "CREATE TABLE IF NOT EXISTS grid_health_counts ("
              + "node_id VARCHAR(256) PRIMARY KEY, "
              + "health_count INTEGER NOT NULL)");
    } catch (SQLException e) {
      LOG.severe("Failed to create tables: " + e.getMessage());
      throw new RuntimeException("Failed to initialize database tables", e);
    }
  }

  @Override
  public void addNode(NodeStatus node) {
    Require.nonNull("Node", node);
    String nodeJson = json.toJson(node);
    
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT OR REPLACE INTO grid_nodes (node_id, node_data, availability) VALUES (?, ?, ?)")) {
      statement.setString(1, node.getNodeId().toString());
      statement.setString(2, nodeJson);
      statement.setString(3, node.getAvailability().toString());
      statement.executeUpdate();
    } catch (SQLException e) {
      LOG.warning("Failed to add node " + node.getNodeId() + ": " + e.getMessage());
      throw new RuntimeException("Failed to add node", e);
    }
  }

  @Override
  public void removeNode(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    
    try (PreparedStatement statement = connection.prepareStatement(
        "DELETE FROM grid_nodes WHERE node_id = ?")) {
      statement.setString(1, nodeId.toString());
      statement.executeUpdate();
      
      removePurgeTime(nodeId);
      removeHealthCount(nodeId);
    } catch (SQLException e) {
      LOG.warning("Failed to remove node " + nodeId + ": " + e.getMessage());
      throw new RuntimeException("Failed to remove node", e);
    }
  }

  @Override
  public void updateNode(NodeStatus node) {
    Require.nonNull("Node", node);
    addNode(node);
  }

  @Override
  public Set<NodeStatus> getAllNodes() {
    Set<NodeStatus> nodes = new HashSet<>();
    
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT node_data FROM grid_nodes");
         ResultSet resultSet = statement.executeQuery()) {
      
      while (resultSet.next()) {
        String nodeJson = resultSet.getString("node_data");
        try {
          NodeStatus node = json.toType(nodeJson, NodeStatus.class);
          nodes.add(node);
        } catch (Exception e) {
          LOG.warning("Failed to deserialize node: " + e.getMessage());
        }
      }
    } catch (SQLException e) {
      LOG.warning("Failed to get all nodes: " + e.getMessage());
      throw new RuntimeException("Failed to get all nodes", e);
    }
    
    return nodes;
  }

  @Override
  public void setPurgeTime(NodeId nodeId, Instant time) {
    Require.nonNull("Node ID", nodeId);
    Require.nonNull("Time", time);
    
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT OR REPLACE INTO grid_purge_times (node_id, purge_time) VALUES (?, ?)")) {
      statement.setString(1, nodeId.toString());
      statement.setTimestamp(2, Timestamp.from(time));
      statement.executeUpdate();
    } catch (SQLException e) {
      LOG.warning("Failed to set purge time for node " + nodeId + ": " + e.getMessage());
      throw new RuntimeException("Failed to set purge time", e);
    }
  }

  @Override
  public Instant getPurgeTime(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT purge_time FROM grid_purge_times WHERE node_id = ?")) {
      statement.setString(1, nodeId.toString());
      
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          Timestamp timestamp = resultSet.getTimestamp("purge_time");
          return timestamp != null ? timestamp.toInstant() : null;
        }
      }
    } catch (SQLException e) {
      LOG.warning("Failed to get purge time for node " + nodeId + ": " + e.getMessage());
    }
    
    return null;
  }

  @Override
  public void removePurgeTime(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    
    try (PreparedStatement statement = connection.prepareStatement(
        "DELETE FROM grid_purge_times WHERE node_id = ?")) {
      statement.setString(1, nodeId.toString());
      statement.executeUpdate();
    } catch (SQLException e) {
      LOG.warning("Failed to remove purge time for node " + nodeId + ": " + e.getMessage());
    }
  }

  @Override
  public void setHealthCount(NodeId nodeId, int count) {
    Require.nonNull("Node ID", nodeId);
    
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT OR REPLACE INTO grid_health_counts (node_id, health_count) VALUES (?, ?)")) {
      statement.setString(1, nodeId.toString());
      statement.setInt(2, count);
      statement.executeUpdate();
    } catch (SQLException e) {
      LOG.warning("Failed to set health count for node " + nodeId + ": " + e.getMessage());
      throw new RuntimeException("Failed to set health count", e);
    }
  }

  @Override
  public int getHealthCount(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT health_count FROM grid_health_counts WHERE node_id = ?")) {
      statement.setString(1, nodeId.toString());
      
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return resultSet.getInt("health_count");
        }
      }
    } catch (SQLException e) {
      LOG.warning("Failed to get health count for node " + nodeId + ": " + e.getMessage());
    }
    
    return 0;
  }

  @Override
  public void removeHealthCount(NodeId nodeId) {
    Require.nonNull("Node ID", nodeId);
    
    try (PreparedStatement statement = connection.prepareStatement(
        "DELETE FROM grid_health_counts WHERE node_id = ?")) {
      statement.setString(1, nodeId.toString());
      statement.executeUpdate();
    } catch (SQLException e) {
      LOG.warning("Failed to remove health count for node " + nodeId + ": " + e.getMessage());
    }
  }

  @Override
  public boolean isReady() {
    try {
      return !connection.isClosed();
    } catch (SQLException e) {
      LOG.warning("Failed to check connection status: " + e.getMessage());
      return false;
    }
  }
}
