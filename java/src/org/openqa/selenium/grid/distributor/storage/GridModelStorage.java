//
//

package org.openqa.selenium.grid.distributor.storage;

import java.time.Instant;
import java.util.Set;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeStatus;

public interface GridModelStorage {

  void addNode(NodeStatus node);

  void removeNode(NodeId nodeId);

  void updateNode(NodeStatus node);

  Set<NodeStatus> getAllNodes();

  void setPurgeTime(NodeId nodeId, Instant time);

  Instant getPurgeTime(NodeId nodeId);

  void removePurgeTime(NodeId nodeId);

  void setHealthCount(NodeId nodeId, int count);

  int getHealthCount(NodeId nodeId);

  void removeHealthCount(NodeId nodeId);

  boolean isReady();
}
