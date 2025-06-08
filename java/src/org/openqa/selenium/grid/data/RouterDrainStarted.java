//
//

package org.openqa.selenium.grid.data;

import java.util.function.Consumer;
import org.openqa.selenium.events.Event;
import org.openqa.selenium.events.EventListener;
import org.openqa.selenium.events.EventName;
import org.openqa.selenium.internal.Require;

public class RouterDrainStarted extends Event {

  private static final EventName ROUTER_DRAIN_STARTED = new EventName("router-drain-started");

  public RouterDrainStarted(NodeId id) {
    super(ROUTER_DRAIN_STARTED, id);
  }

  public static EventListener<NodeId> listener(Consumer<NodeId> handler) {
    Require.nonNull("Handler", handler);

    return new EventListener<>(ROUTER_DRAIN_STARTED, NodeId.class, handler);
  }
}
