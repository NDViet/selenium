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

package org.openqa.selenium.grid.distributor.redis;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.data.CreateSessionRequest;
import org.openqa.selenium.grid.data.CreateSessionResponse;
import org.openqa.selenium.grid.data.DistributorStatus;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.SessionRequest;
import org.openqa.selenium.grid.data.SlotId;
import org.openqa.selenium.grid.data.SlotMatcher;
import org.openqa.selenium.grid.distributor.Distributor;
import org.openqa.selenium.grid.distributor.config.DistributorOptions;
import org.openqa.selenium.grid.distributor.selector.SlotSelector;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.security.SecretOptions;
import org.openqa.selenium.grid.server.EventBusOptions;
import org.openqa.selenium.grid.server.NetworkOptions;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.grid.sessionmap.config.SessionMapOptions;
import org.openqa.selenium.grid.sessionqueue.NewSessionQueue;
import org.openqa.selenium.grid.sessionqueue.config.NewSessionQueueOptions;
import org.openqa.selenium.internal.Either;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

public class RedisBackedDistributor extends Distributor {

  private final RedisBackedNodeRegistry nodeRegistry;
  private final SessionMap sessions;
  private final SlotSelector slotSelector;
  private final SlotMatcher slotMatcher;

  public RedisBackedDistributor(
      Tracer tracer,
      EventBus bus,
      HttpClient.Factory clientFactory,
      SessionMap sessions,
      NewSessionQueue sessionQueue,
      SlotSelector slotSelector,
      Secret registrationSecret,
      Duration healthcheckInterval,
      boolean rejectUnsupportedCaps,
      Duration sessionRequestRetryInterval,
      int newSessionThreadPoolSize,
      SlotMatcher slotMatcher,
      Duration purgeNodesInterval,
      RedisBackedNodeRegistry nodeRegistry) {
    super(tracer, clientFactory, registrationSecret);
    this.nodeRegistry = Require.nonNull("Node registry", nodeRegistry);
    this.sessions = Require.nonNull("Session map", sessions);
    this.slotSelector = Require.nonNull("Slot selector", slotSelector);
    this.slotMatcher = slotMatcher;
  }

  public static Distributor create(Config config) {
    Tracer tracer = new LoggingOptions(config).getTracer();
    EventBus bus = new EventBusOptions(config).getEventBus();
    DistributorOptions distributorOptions = new DistributorOptions(config);
    HttpClient.Factory clientFactory = new NetworkOptions(config).getHttpClientFactory(tracer);
    SessionMap sessions = new SessionMapOptions(config).getSessionMap();
    SecretOptions secretOptions = new SecretOptions(config);
    NewSessionQueueOptions newSessionQueueOptions = new NewSessionQueueOptions(config);
    NewSessionQueue sessionQueue =
        newSessionQueueOptions.getSessionQueue(
            "org.openqa.selenium.grid.sessionqueue.remote.RemoteNewSessionQueue");

    RedisBackedNodeRegistry nodeRegistry = RedisBackedNodeRegistry.create(config);

    return new RedisBackedDistributor(
        tracer,
        bus,
        clientFactory,
        sessions,
        sessionQueue,
        distributorOptions.getSlotSelector(),
        secretOptions.getRegistrationSecret(),
        distributorOptions.getHealthCheckInterval(),
        distributorOptions.shouldRejectUnsupportedCaps(),
        newSessionQueueOptions.getSessionRequestRetryInterval(),
        distributorOptions.getNewSessionThreadPoolSize(),
        distributorOptions.getSlotMatcher(),
        distributorOptions.getPurgeNodesInterval(),
        nodeRegistry);
  }

  @Override
  public boolean isReady() {
    return nodeRegistry.isReady();
  }

  @Override
  public RedisBackedDistributor add(Node node) {
    nodeRegistry.add(node);
    return this;
  }

  @Override
  public boolean drain(NodeId nodeId) {
    return nodeRegistry.drain(nodeId);
  }

  public void remove(NodeId nodeId) {
    nodeRegistry.remove(nodeId);
  }

  @Override
  public DistributorStatus getStatus() {
    return nodeRegistry.getStatus();
  }

  protected Set<NodeStatus> getAvailableNodes() {
    return nodeRegistry.getAvailableNodes();
  }

  @Override
  public Either<SessionNotCreatedException, CreateSessionResponse> newSession(
      SessionRequest request) throws SessionNotCreatedException {
    Require.nonNull("Session request", request);

    if (request.getDesiredCapabilities().isEmpty()) {
      return Either.left(new SessionNotCreatedException("No capabilities found in session request"));
    }

    SessionNotCreatedException lastFailure = new SessionNotCreatedException("Unable to create session");
    
    for (Capabilities caps : request.getDesiredCapabilities()) {
      // Find available slot
      SlotId selectedSlot = reserveSlot(caps);
      if (selectedSlot == null) {
        continue;
      }

      // Get node and create session
      Node node = nodeRegistry.getNode(selectedSlot.getOwningNodeId());
      if (node == null) {
        nodeRegistry.setSession(selectedSlot, null);
        continue;
      }

      try {
        CreateSessionRequest singleRequest = new CreateSessionRequest(
            request.getDownstreamDialects(), caps, request.getMetadata());
        
        Either<WebDriverException, CreateSessionResponse> result = node.newSession(singleRequest);
        
        if (result.isRight()) {
          CreateSessionResponse response = result.right();
          sessions.add(response.getSession());
          nodeRegistry.setSession(selectedSlot, response.getSession());
          return Either.right(response);
        } else {
          nodeRegistry.setSession(selectedSlot, null);
          lastFailure = new SessionNotCreatedException(result.left().getMessage(), result.left());
        }
      } catch (Exception e) {
        nodeRegistry.setSession(selectedSlot, null);
        lastFailure = new SessionNotCreatedException(e.getMessage(), e);
      }
    }

    return Either.left(lastFailure);
  }

  private SlotId reserveSlot(Capabilities caps) {
    Set<SlotId> slotIds = slotSelector.selectSlot(caps, getAvailableNodes(), slotMatcher);
    if (slotIds.isEmpty()) {
      return null;
    }

    for (SlotId slotId : slotIds) {
      if (nodeRegistry.reserve(slotId)) {
        return slotId;
      }
    }
    return null;
  }

  protected Node getNodeFromURI(URI uri) {
    return nodeRegistry.getNode(uri);
  }
}
