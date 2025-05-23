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

package org.openqa.selenium.grid.router;

import static org.openqa.selenium.remote.http.HttpMethod.GET;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.HttpSessionId;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.BinaryMessage;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.CloseMessage;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.Message;
import org.openqa.selenium.remote.http.TextMessage;
import org.openqa.selenium.remote.http.WebSocket;

public class ProxyWebsocketsIntoGrid
    implements BiFunction<String, Consumer<Message>, Optional<Consumer<Message>>> {

  private static final Logger LOG = Logger.getLogger(ProxyWebsocketsIntoGrid.class.getName());
  private final HttpClient.Factory clientFactory;
  private final SessionMap sessions;
  private final Set<WebSocket> activeWebSockets;
  private final Set<WebSocket> closedWebSockets;

  public ProxyWebsocketsIntoGrid(HttpClient.Factory clientFactory, SessionMap sessions) {
    this.clientFactory = Objects.requireNonNull(clientFactory);
    this.sessions = Objects.requireNonNull(sessions);
    this.activeWebSockets = Collections.synchronizedSet(new HashSet<>());
    this.closedWebSockets = Collections.synchronizedSet(new HashSet<>());
  }

  @Override
  public Optional<Consumer<Message>> apply(String uri, Consumer<Message> downstream) {
    Require.nonNull("uri", uri);
    Require.nonNull("downstream", downstream);

    Optional<SessionId> sessionId = HttpSessionId.getSessionId(uri).map(SessionId::new);
    if (sessionId.isEmpty()) {
      LOG.warning("Session not found for uri " + uri);
      return Optional.empty();
    }

    try {
      URI sessionUri = sessions.getUri(sessionId.get());

      HttpClient client =
          clientFactory.createClient(ClientConfig.defaultConfig().baseUri(sessionUri));
      try {
        WebSocket upstream =
            client.openSocket(new HttpRequest(GET, uri), new ForwardingListener(downstream, client));
        activeWebSockets.add(upstream);

        return Optional.of(
            (msg) -> {
              try {
                upstream.send(msg);
              } finally {
                if (msg instanceof CloseMessage) {
                  try {
                    activeWebSockets.remove(upstream);
                    closedWebSockets.add(upstream);
                    client.close();
                  } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to shutdown the client of " + sessionUri, e);
                  }
                }
              }
            });
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Connecting to upstream websocket failed", e);
        if (!activeWebSockets.isEmpty()) {
          LOG.fine("Removing " + activeWebSockets.size() + " active websockets");
          activeWebSockets.clear();
        }
        client.close();
        return Optional.empty();
      }
    } catch (NoSuchSessionException e) {
      LOG.warning("Attempt to connect to non-existent session: " + uri);
      return Optional.empty();
    }
  }

  private static class ForwardingListener implements WebSocket.Listener {
    private final Consumer<Message> downstream;
    private final HttpClient client;

    public ForwardingListener(Consumer<Message> downstream, HttpClient client) {
      this.downstream = Objects.requireNonNull(downstream);
      this.client = Objects.requireNonNull(client);
    }

    @Override
    public void onBinary(byte[] data) {
      downstream.accept(new BinaryMessage(data));
    }

    @Override
    public void onClose(int code, String reason) {
      downstream.accept(new CloseMessage(code, reason));
      try {
        client.close();
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Failed to close client after WebSocket closed", e);
      }
    }

    @Override
    public void onText(CharSequence data) {
      downstream.accept(new TextMessage(data));
    }

    @Override
    public void onError(Throwable cause) {
      LOG.log(Level.WARNING, "Error proxying websocket command", cause);
    }
  }
}
