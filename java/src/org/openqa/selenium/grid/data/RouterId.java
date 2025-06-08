//
//

package org.openqa.selenium.grid.data;

import java.util.Objects;
import java.util.UUID;
import org.openqa.selenium.internal.Require;

public class RouterId {
  private final UUID id;

  public RouterId(UUID id) {
    this.id = Require.nonNull("Router ID", id);
  }

  public UUID getId() {
    return id;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    RouterId routerId = (RouterId) o;
    return Objects.equals(id, routerId.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @Override
  public String toString() {
    return id.toString();
  }
}
