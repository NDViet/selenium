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

package org.openqa.selenium.grid.node;

import static org.openqa.selenium.internal.Debug.getDebugLogLevel;
import static org.openqa.selenium.remote.http.HttpMethod.GET;

import com.google.common.collect.ImmutableSet;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.devtools.CdpEndpointFinder;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.BinaryMessage;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.CloseMessage;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.Message;
import org.openqa.selenium.remote.http.TextMessage;
import org.openqa.selenium.remote.http.UrlTemplate;
import org.openqa.selenium.remote.http.WebSocket;

public class ProxyNodeWebsockets
    implements BiFunction<String, Consumer<Message>, Optional<Consumer<Message>>> {

  private static final UrlTemplate CDP_TEMPLATE = new UrlTemplate("/session/{sessionId}/se/cdp");
  private static final UrlTemplate BIDI_TEMPLATE = new UrlTemplate("/session/{sessionId}/se/bidi");
  private static final UrlTemplate FWD_TEMPLATE = new UrlTemplate("/session/{sessionId}/se/fwd");
  private static final UrlTemplate VNC_TEMPLATE = new UrlTemplate("/session/{sessionId}/se/vnc");
  private static final Logger LOG = Logger.getLogger(ProxyNodeWebsockets.class.getName());
  private static final ImmutableSet<String> CDP_ENDPOINT_CAPS =
      ImmutableSet.of("goog:chromeOptions", "ms:edgeOptions");
  private final HttpClient.Factory clientFactory;
  private final Node node;
  private final String gridSubPath;
  private final Map<SessionId, Set<WebSocket>> activeWebSockets;
  private final Map<SessionId, Set<HttpClient>> activeClients;

  public ProxyNodeWebsockets(HttpClient.Factory clientFactory, Node node, String gridSubPath) {
    this.clientFactory = Objects.requireNonNull(clientFactory);
    this.node = Objects.requireNonNull(node);
    this.gridSubPath = gridSubPath;
    this.activeWebSockets = Collections.synchronizedMap(new HashMap<>());
    this.activeClients = Collections.synchronizedMap(new HashMap<>());
  }
  
  /**
   * Cleans up all WebSocket connections and HttpClient instances for a specific session.
   * This should be called when a session is terminated to prevent memory leaks.
   *
   * @param sessionId The ID of the session to clean up
   */
  public void cleanUpSession(SessionId sessionId) {
    if (sessionId == null) {
      return;
    }
    
    LOG.fine("Cleaning up WebSocket connections for session: " + sessionId);
    
    synchronized (activeWebSockets) {
      Set<WebSocket> sockets = activeWebSockets.remove(sessionId);
      if (sockets != null && !sockets.isEmpty()) {
        LOG.fine("Closing " + sockets.size() + " WebSocket connections for session: " + sessionId);
      }
    }
    
    synchronized (activeClients) {
      Set<HttpClient> clients = activeClients.remove(sessionId);
      if (clients != null && !clients.isEmpty()) {
        LOG.fine("Closing " + clients.size() + " HttpClient instances for session: " + sessionId);
        for (HttpClient client : new ArrayList<>(clients)) {
          try {
            client.close();
          } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to close HttpClient for session: " + sessionId, e);
          }
        }
      }
    }
  }

  @Override
  public Optional<Consumer<Message>> apply(String uri, Consumer<Message> downstream) {
    UrlTemplate.Match fwdMatch = FWD_TEMPLATE.match(uri, gridSubPath);
    UrlTemplate.Match cdpMatch = CDP_TEMPLATE.match(uri, gridSubPath);
    UrlTemplate.Match bidiMatch = BIDI_TEMPLATE.match(uri, gridSubPath);
    UrlTemplate.Match vncMatch = VNC_TEMPLATE.match(uri, gridSubPath);

    if (bidiMatch == null && cdpMatch == null && vncMatch == null && fwdMatch == null) {
      return Optional.empty();
    }

    Optional<UrlTemplate.Match> firstMatch =
        Stream.of(fwdMatch, cdpMatch, bidiMatch, vncMatch).filter(Objects::nonNull).findFirst();

    if (firstMatch.isEmpty()) {
      LOG.warning("No session id found in uri " + uri);
      return Optional.empty();
    }

    String sessionId = firstMatch.get().getParameters().get("sessionId");

    LOG.fine("Matching websockets for session id: " + sessionId);
    SessionId id = new SessionId(sessionId);

    if (!node.isSessionOwner(id)) {
      LOG.warning("Not owner of " + id);
      return Optional.empty();
    }

    // ensure one session does not open to many connections, this might have a negative impact on
    // the grid health
    if (!node.tryAcquireConnection(id)) {
      LOG.warning("Too many websocket connections initiated by " + id);
      return Optional.empty();
    }

    Session session = node.getSession(id);
    Capabilities caps = session.getCapabilities();
    LOG.fine("Scanning for endpoint: " + caps);

    // Used by the ForwardingListener to notify the node that the session is still active
    Consumer<SessionId> sessionConsumer = node::isSessionOwner;

    if (bidiMatch != null) {
      return findBiDiEndpoint(downstream, caps, sessionConsumer, id);
    }

    if (vncMatch != null) {
      // Passing a fake consumer to the ForwardingListener to avoid sending a session notification
      // when VNC is used.
      sessionConsumer = fakeConsumer -> {};
      return findVncEndpoint(downstream, caps, sessionConsumer, id);
    }

    // This match happens when a user wants to do CDP over Dynamic Grid
    if (fwdMatch != null) {
      LOG.info("Matched endpoint where CDP connection is being forwarded");
      return findCdpEndpoint(downstream, caps, sessionConsumer, id);
    }
    if (caps.getCapabilityNames().contains("se:forwardCdp")) {
      LOG.info("Found endpoint where CDP connection needs to be forwarded");
      return findForwardCdpEndpoint(downstream, caps, sessionConsumer, id);
    }
    return findCdpEndpoint(downstream, caps, sessionConsumer, id);
  }

  private Optional<Consumer<Message>> findCdpEndpoint(
      Consumer<Message> downstream,
      Capabilities caps,
      Consumer<SessionId> sessionConsumer,
      SessionId sessionId) {

    for (String cdpEndpointCap : CDP_ENDPOINT_CAPS) {
      Optional<URI> reportedUri = CdpEndpointFinder.getReportedUri(cdpEndpointCap, caps);
      Optional<HttpClient> client =
          reportedUri.map(uri -> CdpEndpointFinder.getHttpClient(clientFactory, uri));
      Optional<URI> cdpUri;

      try {
        cdpUri = client.flatMap(CdpEndpointFinder::getCdpEndPoint);
      } catch (Exception e) {
        try {
          client.ifPresent(HttpClient::close);
        } catch (Exception ex) {
          e.addSuppressed(ex);
        }
        throw e;
      }

      if (cdpUri.isPresent()) {
        LOG.log(getDebugLogLevel(), String.format("Endpoint found in %s", cdpEndpointCap));
        return cdpUri.map(cdp -> createWsEndPoint(cdp, downstream, sessionConsumer, sessionId));
      } else {
        try {
          client.ifPresent(HttpClient::close);
        } catch (Exception e) {
          LOG.log(
              Level.FINE,
              "failed to close the http client used to check the reported CDP endpoint: "
                  + reportedUri.get(),
              e);
        }
      }
    }
    return Optional.empty();
  }

  private Optional<Consumer<Message>> findBiDiEndpoint(
      Consumer<Message> downstream,
      Capabilities caps,
      Consumer<SessionId> sessionConsumer,
      SessionId sessionId) {
    try {
      URI uri = new URI(String.valueOf(caps.getCapability("se:gridWebSocketUrl")));
      return Optional.of(uri)
          .map(bidi -> createWsEndPoint(bidi, downstream, sessionConsumer, sessionId));
    } catch (URISyntaxException e) {
      LOG.warning("Unable to create URI from: " + caps.getCapability("webSocketUrl"));
      return Optional.empty();
    }
  }

  private Optional<Consumer<Message>> findForwardCdpEndpoint(
      Consumer<Message> downstream,
      Capabilities caps,
      Consumer<SessionId> sessionConsumer,
      SessionId sessionId) {
    // When using Dynamic Grid, we need to connect to a container before using the debuggerAddress
    try {
      URI uri = new URI(String.valueOf(caps.getCapability("se:forwardCdp")));
      return Optional.of(uri)
          .map(cdp -> createWsEndPoint(cdp, downstream, sessionConsumer, sessionId));
    } catch (URISyntaxException e) {
      LOG.warning("Unable to create URI from: " + caps.getCapability("se:forwardCdp"));
      return Optional.empty();
    }
  }

  private Optional<Consumer<Message>> findVncEndpoint(
      Consumer<Message> downstream,
      Capabilities caps,
      Consumer<SessionId> sessionConsumer,
      SessionId sessionId) {
    String vncLocalAddress = (String) caps.getCapability("se:vncLocalAddress");
    Optional<URI> vncUri;
    try {
      vncUri = Optional.of(new URI(vncLocalAddress));
    } catch (URISyntaxException e) {
      LOG.warning("Invalid URI for endpoint " + vncLocalAddress);
      return Optional.empty();
    }
    LOG.log(getDebugLogLevel(), String.format("Endpoint found in %s", "se:vncLocalAddress"));
    return vncUri.map(vnc -> createWsEndPoint(vnc, downstream, sessionConsumer, sessionId));
  }

  private Consumer<Message> createWsEndPoint(
      URI uri,
      Consumer<Message> downstream,
      Consumer<SessionId> sessionConsumer,
      SessionId sessionId) {
    Require.nonNull("downstream", downstream);
    Require.nonNull("uri", uri);
    Require.nonNull("sessionConsumer", sessionConsumer);
    Require.nonNull("sessionId", sessionId);

    LOG.info("Establishing connection to " + uri);

    HttpClient client = clientFactory.createClient(ClientConfig.defaultConfig().baseUri(uri));
    
    synchronized (activeClients) {
      activeClients.computeIfAbsent(sessionId, id -> Collections.synchronizedSet(new HashSet<>()))
          .add(client);
    }
    
    try {
      WebSocket upstream =
          client.openSocket(
              new HttpRequest(GET, uri.toString()),
              new ForwardingListener(node, downstream, sessionConsumer, sessionId, client, this));
      
      synchronized (activeWebSockets) {
        activeWebSockets.computeIfAbsent(sessionId, id -> Collections.synchronizedSet(new HashSet<>()))
            .add(upstream);
      }

      return (msg) -> {
        try {
          upstream.send(msg);
        } finally {
          if (msg instanceof CloseMessage) {
            try {
              synchronized (activeWebSockets) {
                Set<WebSocket> sockets = activeWebSockets.get(sessionId);
                if (sockets != null) {
                  sockets.remove(upstream);
                  if (sockets.isEmpty()) {
                    activeWebSockets.remove(sessionId);
                  }
                }
              }
              
              synchronized (activeClients) {
                Set<HttpClient> clients = activeClients.get(sessionId);
                if (clients != null) {
                  clients.remove(client);
                  if (clients.isEmpty()) {
                    activeClients.remove(sessionId);
                  }
                }
              }
              
              client.close();
            } catch (Exception e) {
              LOG.log(Level.WARNING, "Failed to shutdown the client of " + uri, e);
            }
          }
        }
      };
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Connecting to upstream websocket failed", e);
      
      synchronized (activeClients) {
        Set<HttpClient> clients = activeClients.get(sessionId);
        if (clients != null) {
          clients.remove(client);
          if (clients.isEmpty()) {
            activeClients.remove(sessionId);
          }
        }
      }
      
      client.close();
      throw e;
    }
  }

  private static class ForwardingListener implements WebSocket.Listener {
    private final Node node;
    private final Consumer<Message> downstream;
    private final Consumer<SessionId> sessionConsumer;
    private final SessionId sessionId;
    private final HttpClient client;
    private final ProxyNodeWebsockets proxyNodeWebsockets;

    public ForwardingListener(
        Node node,
        Consumer<Message> downstream,
        Consumer<SessionId> sessionConsumer,
        SessionId sessionId,
        HttpClient client,
        ProxyNodeWebsockets proxyNodeWebsockets) {
      this.node = node;
      this.downstream = Objects.requireNonNull(downstream);
      this.sessionConsumer = Objects.requireNonNull(sessionConsumer);
      this.sessionId = Objects.requireNonNull(sessionId);
      this.client = Objects.requireNonNull(client);
      this.proxyNodeWebsockets = Objects.requireNonNull(proxyNodeWebsockets);
    }

    @Override
    public void onBinary(byte[] data) {
      downstream.accept(new BinaryMessage(data));
      sessionConsumer.accept(sessionId);
    }

    @Override
    public void onClose(int code, String reason) {
      downstream.accept(new CloseMessage(code, reason));
      
      synchronized (proxyNodeWebsockets.activeWebSockets) {
        Set<WebSocket> sockets = proxyNodeWebsockets.activeWebSockets.get(sessionId);
        if (sockets != null) {
          if (sockets.isEmpty()) {
            proxyNodeWebsockets.activeWebSockets.remove(sessionId);
          }
        }
      }
      
      synchronized (proxyNodeWebsockets.activeClients) {
        Set<HttpClient> clients = proxyNodeWebsockets.activeClients.get(sessionId);
        if (clients != null) {
          clients.remove(client);
          if (clients.isEmpty()) {
            proxyNodeWebsockets.activeClients.remove(sessionId);
          }
        }
      }
      
      try {
        client.close();
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Failed to close client during WebSocket close", e);
      }
      
      node.releaseConnection(sessionId);
    }

    @Override
    public void onText(CharSequence data) {
      downstream.accept(new TextMessage(data));
      sessionConsumer.accept(sessionId);
    }

    @Override
    public void onError(Throwable cause) {
      LOG.log(Level.WARNING, "Error proxying websocket command", cause);
    }
  }
}
