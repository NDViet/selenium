//
//

package org.openqa.selenium.grid.data;

import java.util.function.Consumer;
import org.openqa.selenium.events.Event;
import org.openqa.selenium.events.EventListener;
import org.openqa.selenium.events.EventName;
import org.openqa.selenium.internal.Require;

public class RouterDrainComplete extends Event {
  private static final EventName ROUTER_DRAIN_COMPLETE = new EventName("router-drain-complete");

  public RouterDrainComplete(RouterId id) {
    super(ROUTER_DRAIN_COMPLETE, id);
  }

  public static EventListener<RouterId> listener(Consumer<RouterId> handler) {
    Require.nonNull("Handler", handler);

    return new EventListener<>(ROUTER_DRAIN_COMPLETE, RouterId.class, handler);
  }
}
