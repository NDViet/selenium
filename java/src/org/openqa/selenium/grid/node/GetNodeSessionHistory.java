//
//

package org.openqa.selenium.grid.node;

import static org.openqa.selenium.remote.http.Contents.asJson;

import com.google.common.collect.ImmutableMap;
import java.io.UncheckedIOException;
import java.util.List;
import org.openqa.selenium.grid.data.SessionHistoryEntry;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

class GetNodeSessionHistory implements HttpHandler {

  private final Node node;

  GetNodeSessionHistory(Node node) {
    this.node = Require.nonNull("Node", node);
  }

  @Override
  public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
    List<SessionHistoryEntry> sessionHistory = node.getSessionHistory();

    return new HttpResponse().setContent(asJson(ImmutableMap.of("value", sessionHistory)));
  }
}
