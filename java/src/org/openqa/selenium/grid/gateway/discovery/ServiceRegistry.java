package org.openqa.selenium.grid.gateway.discovery;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** Service registry interface for dynamic Grid instance discovery. */
public interface ServiceRegistry {

  void registerInstance(String instanceId, URI baseUri, Map<String, String> metadata);

  void unregisterInstance(String instanceId);

  void updateHeartbeat(String instanceId);

  Map<String, ServiceInstance> getInstances();

  void addChangeListener(ServiceRegistryChangeListener listener);

  void removeChangeListener(ServiceRegistryChangeListener listener);

  void close();

  class ServiceInstance {
    private final String instanceId;
    private final URI baseUri;
    private final Map<String, String> metadata;
    private final Instant registeredAt;
    private volatile Instant lastHeartbeat;

    public ServiceInstance(String instanceId, URI baseUri, Map<String, String> metadata) {
      this.instanceId = instanceId;
      this.baseUri = baseUri;
      this.metadata = metadata;
      this.registeredAt = Instant.now();
      this.lastHeartbeat = Instant.now();
    }

    public String getInstanceId() {
      return instanceId;
    }

    public URI getBaseUri() {
      return baseUri;
    }

    public Map<String, String> getMetadata() {
      return metadata;
    }

    public Instant getRegisteredAt() {
      return registeredAt;
    }

    public Instant getLastHeartbeat() {
      return lastHeartbeat;
    }

    public void updateHeartbeat() {
      this.lastHeartbeat = Instant.now();
    }
  }

  interface ServiceRegistryChangeListener {
    void onInstanceRegistered(ServiceInstance instance);

    void onInstanceUnregistered(String instanceId);

    void onInstanceUpdated(ServiceInstance instance);
  }
}
