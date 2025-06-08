//
//

package org.openqa.selenium.grid.distributor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.openqa.selenium.grid.config.MapConfig;
import org.openqa.selenium.grid.data.Availability;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.Slot;
import org.openqa.selenium.grid.data.SlotId;
import org.openqa.selenium.grid.distributor.storage.GridModelStorage;
import org.openqa.selenium.grid.distributor.storage.local.LocalGridModelStorage;
import org.openqa.selenium.platform.Platform;

class GridModelTest {

  private EventBus eventBus;
  private GridModelStorage storage;
  private GridModel model;
  private NodeStatus nodeStatus;
  private NodeId nodeId;

  @BeforeEach
  void setUp() {
    eventBus = new GuavaEventBus();
    storage = new LocalGridModelStorage();
    model = new GridModel(eventBus, storage);
    nodeId = new NodeId(UUID.randomUUID());
    
    Capabilities caps = new ImmutableCapabilities("browserName", "chrome");
    Slot slot = new Slot(new SlotId(nodeId, UUID.randomUUID()), caps, Instant.now(), null);
    
    nodeStatus = new NodeStatus(
        nodeId,
        URI.create("http://localhost:4444"),
        1,
        Set.of(slot),
        Availability.UP,
        Duration.ofSeconds(30),
        Duration.ofMinutes(5),
        "4.0.0",
        Platform.getCurrent().family().toString());
  }

  @Test
  void shouldCreateGridModelFromConfig() {
    Map<String, Object> config = Map.of(
        "gridmodel", Map.of(
            "implementation", "org.openqa.selenium.grid.distributor.storage.local.LocalGridModelStorage"
        )
    );
    
    GridModel model = GridModel.create(new MapConfig(config));
    
    assertThat(model).isNotNull();
  }

  @Test
  void shouldAddNodeToStorage() {
    model.add(nodeStatus);
    
    Set<NodeStatus> nodes = model.getSnapshot();
    assertThat(nodes).hasSize(1);
    assertThat(nodes.iterator().next().getNodeId()).isEqualTo(nodeId);
  }

  @Test
  void shouldRemoveNodeFromStorage() {
    model.add(nodeStatus);
    model.remove(nodeId);
    
    Set<NodeStatus> nodes = model.getSnapshot();
    assertThat(nodes).isEmpty();
  }

  @Test
  void shouldRefreshNodeInStorage() {
    model.add(nodeStatus);
    
    NodeStatus updatedStatus = new NodeStatus(
        nodeId,
        URI.create("http://localhost:4444"),
        2,
        nodeStatus.getSlots(),
        Availability.UP,
        Duration.ofSeconds(30),
        Duration.ofMinutes(5),
        "4.0.0",
        Platform.getCurrent().family().toString());
    
    model.refresh(updatedStatus);
    
    Set<NodeStatus> nodes = model.getSnapshot();
    assertThat(nodes).hasSize(1);
    assertThat(nodes.iterator().next().getMaxSessionCount()).isEqualTo(2);
  }

  @Test
  void shouldSetAvailabilityInStorage() {
    model.add(nodeStatus);
    model.setAvailability(nodeId, Availability.DOWN);
    
    Set<NodeStatus> nodes = model.getSnapshot();
    assertThat(nodes).hasSize(1);
    assertThat(nodes.iterator().next().getAvailability()).isEqualTo(Availability.DOWN);
  }

  @Test
  void shouldUpdateHealthCheckCount() {
    model.updateHealthCheckCount(nodeId, Availability.DOWN);
    model.updateHealthCheckCount(nodeId, Availability.DOWN);
    
    assertThat(model.getSnapshot()).isEmpty(); // No nodes added yet
  }

  @Test
  void shouldUseStorageForAllOperations() {
    GridModelStorage mockStorage = mock(GridModelStorage.class);
    when(mockStorage.getAllNodes()).thenReturn(Set.of());
    
    GridModel modelWithMock = new GridModel(eventBus, mockStorage);
    
    modelWithMock.add(nodeStatus);
    verify(mockStorage).addNode(any(NodeStatus.class));
    
    modelWithMock.remove(nodeId);
    verify(mockStorage).removeNode(nodeId);
    
    modelWithMock.getSnapshot();
    verify(mockStorage).getAllNodes();
  }

  @Test
  void shouldDelegateStorageOperationsCorrectly() {
    GridModelStorage mockStorage = mock(GridModelStorage.class);
    when(mockStorage.getAllNodes()).thenReturn(Set.of(nodeStatus));
    when(mockStorage.getHealthCount(nodeId)).thenReturn(0);
    when(mockStorage.getPurgeTime(nodeId)).thenReturn(Instant.now());
    
    GridModel modelWithMock = new GridModel(eventBus, mockStorage);
    
    modelWithMock.purgeDeadNodes();
    
    verify(mockStorage).getAllNodes();
    verify(mockStorage).getHealthCount(nodeId);
    verify(mockStorage).getPurgeTime(nodeId);
  }

  @Test
  void shouldHandleNullPurgeTimeFromStorage() {
    GridModelStorage mockStorage = mock(GridModelStorage.class);
    when(mockStorage.getAllNodes()).thenReturn(Set.of(nodeStatus));
    when(mockStorage.getHealthCount(nodeId)).thenReturn(0);
    when(mockStorage.getPurgeTime(nodeId)).thenReturn(null);
    
    GridModel modelWithMock = new GridModel(eventBus, mockStorage);
    
    modelWithMock.purgeDeadNodes();
    
    verify(mockStorage).getPurgeTime(nodeId);
  }
}
