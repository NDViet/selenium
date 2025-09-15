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

package org.openqa.selenium.grid.gateway;

import static org.openqa.selenium.remote.http.HttpMethod.GET;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
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

/**
 * WebSocket proxy for Gateway that routes WebSocket connections to the appropriate Grid instance
 * based on session affinity and routing rules.
 *
 * <p>This class extends the LoadBalancer's WebSocket proxy functionality to support
 * Gateway-specific routing rules and enhanced session management. It:
 *
 * <ul>
 *   <li>Extracts session ID from WebSocket URI
 *   <li>Looks up the Grid instance hosting that session using Gateway session mapping
 *   <li>Establishes WebSocket connection to the appropriate Grid instance
 *   <li>Proxies WebSocket messages bidirectionally between client and Grid instance
 *   <li>Integrates with Gateway routing rules and enhanced session tracking
 * </ul>
 */
public class ProxyWebsocketsIntoGateway
    implements BiFunction<String, Consumer<Message>, Optional<Consumer<Message>>> {

  private static final Logger LOG = Logger.getLogger(ProxyWebsocketsIntoGateway.class.getName());

  private final HttpClient.Factory clientFactory;
  private final SessionGridMapping sessionGridMapping;
  private final GridInstanceRegistry gridInstanceRegistry;

  public ProxyWebsocketsIntoGateway(
      HttpClient.Factory clientFactory, GridInstanceRegistry gridInstanceRegistry) {
    this.clientFactory = Objects.requireNonNull(clientFactory);
    this.sessionGridMapping = SessionGridMapping.getInstance(gridInstanceRegistry);
    this.gridInstanceRegistry = Objects.requireNonNull(gridInstanceRegistry);
    System.out.println("🔧 ProxyWebsocketsIntoGateway constructor called");
    LOG.severe("🔧 ProxyWebsocketsIntoGateway constructor called");
  }

  @Deprecated
  public ProxyWebsocketsIntoGateway(
      HttpClient.Factory clientFactory,
      SessionGridMapping sessionGridMapping,
      GridInstanceRegistry gridInstanceRegistry) {
    this.clientFactory = Objects.requireNonNull(clientFactory);
    this.sessionGridMapping = SessionGridMapping.getInstance(gridInstanceRegistry);
    this.gridInstanceRegistry = Objects.requireNonNull(gridInstanceRegistry);
  }

  @Override
  public Optional<Consumer<Message>> apply(String uri, Consumer<Message> downstream) {
    System.out.println("🚀 Gateway WebSocket bridge for: " + uri);

    Require.nonNull("uri", uri);
    Require.nonNull("downstream", downstream);

    // Extract session ID from WebSocket URI
    Optional<String> sessionIdStr = HttpSessionId.getSessionId(uri);
    if (sessionIdStr.isEmpty()) {
      System.out.println("❌ No session ID in URI: " + uri);
      return Optional.empty();
    }

    SessionId sessionId = new SessionId(sessionIdStr.get());
    System.out.println("🔍 Looking for session: " + sessionId);

    // Get first available Grid instance if session not mapped
    Optional<String> gridInstanceId = sessionGridMapping.getGridInstanceId(sessionId);
    if (gridInstanceId.isEmpty()) {
      System.out.println("⚠️ Session not in mapping, using first available Grid instance");
      Optional<org.openqa.selenium.grid.gateway.GridInstance> firstInstance =
          gridInstanceRegistry.getAvailableGridInstances().stream().findFirst();
      if (firstInstance.isEmpty()) {
        System.out.println("❌ No Grid instances available");
        return Optional.empty();
      }
      gridInstanceId = Optional.of(firstInstance.get().getId());
    }

    Optional<org.openqa.selenium.grid.gateway.GridInstance> gridInstance =
        gridInstanceRegistry.getGridInstance(gridInstanceId.get());
    if (gridInstance.isEmpty()) {
      System.out.println("❌ Grid instance not found: " + gridInstanceId.get());
      return Optional.empty();
    }

    URI targetUri = gridInstance.get().getBaseUri();
    System.out.println("🌍 Bridging to: " + targetUri + uri);

    try {
      HttpClient client =
          clientFactory.createClient(ClientConfig.defaultConfig().baseUri(targetUri));
      WebSocket upstream =
          client.openSocket(new HttpRequest(GET, uri), new ForwardingListener(downstream));
      System.out.println("✅ WebSocket bridge established");

      return Optional.of(
          msg -> {
            try {
              upstream.send(msg);
            } finally {
              if (msg instanceof CloseMessage) {
                try {
                  client.close();
                } catch (Exception e) {
                  System.out.println("❌ Error closing client: " + e.getMessage());
                }
              }
            }
          });
    } catch (Exception e) {
      System.out.println("❌ Failed to connect: " + e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Forwards messages from Grid instance back to client. This completes the bidirectional bridge:
   * Client <-> Gateway <-> Grid Instance
   */
  private static class ForwardingListener implements WebSocket.Listener {
    private final Consumer<Message> downstream;

    public ForwardingListener(Consumer<Message> downstream) {
      this.downstream = Objects.requireNonNull(downstream);
    }

    @Override
    public void onBinary(byte[] data) {
      // Forward binary data from Grid instance to client
      downstream.accept(new BinaryMessage(data));
    }

    @Override
    public void onClose(int code, String reason) {
      // Forward close message from Grid instance to client
      downstream.accept(new CloseMessage(code, reason));
    }

    @Override
    public void onText(CharSequence data) {
      // Forward text data from Grid instance to client
      downstream.accept(new TextMessage(data));
    }

    @Override
    public void onError(Throwable cause) {
      LOG.log(
          Level.WARNING,
          "Error in WebSocket bridge - forwarding from Grid instance to client",
          cause);
    }
  }
}
