package org.openqa.selenium.grid.gateway.discovery;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * HTTP-based service registry implementation for simple deployments. Stores registrations in memory
 * and provides REST API for registration.
 */
public class HttpServiceRegistry implements ServiceRegistry {

  private static final Logger LOG = Logger.getLogger(HttpServiceRegistry.class.getName());

  private final ConcurrentMap<String, ServiceInstance> instances;
  private final CopyOnWriteArrayList<ServiceRegistryChangeListener> listeners;
  private final ScheduledExecutorService cleanupExecutor;
  private final Duration instanceTimeout;

  public HttpServiceRegistry(Duration instanceTimeout) {
    this.instances = new ConcurrentHashMap<>();
    this.listeners = new CopyOnWriteArrayList<>();
    this.instanceTimeout = instanceTimeout;

    this.cleanupExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r);
              thread.setDaemon(true);
              thread.setName("HttpServiceRegistry - Cleanup");
              return thread;
            });

    // Start periodic cleanup of stale instances
    startPeriodicCleanup();
  }

  @Override
  public void registerInstance(String instanceId, URI baseUri, Map<String, String> metadata) {
    ServiceInstance instance = new ServiceInstance(instanceId, baseUri, metadata);
    ServiceInstance previous = instances.put(instanceId, instance);

    if (previous == null) {
      LOG.info("Registered new instance: " + instanceId + " at " + baseUri);
      notifyInstanceRegistered(instance);
    } else {
      LOG.fine("Updated existing instance: " + instanceId);
      notifyInstanceUpdated(instance);
    }
  }

  @Override
  public void unregisterInstance(String instanceId) {
    ServiceInstance removed = instances.remove(instanceId);
    if (removed != null) {
      LOG.info("Unregistered instance: " + instanceId);
      notifyInstanceUnregistered(instanceId);
    }
  }

  @Override
  public void updateHeartbeat(String instanceId) {
    ServiceInstance instance = instances.get(instanceId);
    if (instance != null) {
      instance.updateHeartbeat();
    }
  }

  @Override
  public Map<String, ServiceInstance> getInstances() {
    return Map.copyOf(instances);
  }

  @Override
  public void addChangeListener(ServiceRegistryChangeListener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeChangeListener(ServiceRegistryChangeListener listener) {
    listeners.remove(listener);
  }

  @Override
  public void close() {
    cleanupExecutor.shutdown();
  }

  private void startPeriodicCleanup() {
    cleanupExecutor.scheduleAtFixedRate(
        this::cleanupStaleInstances,
        instanceTimeout.toSeconds(),
        instanceTimeout.toSeconds(),
        TimeUnit.SECONDS);
  }

  private void cleanupStaleInstances() {
    Instant cutoff = Instant.now().minus(instanceTimeout);

    instances
        .entrySet()
        .removeIf(
            entry -> {
              ServiceInstance instance = entry.getValue();
              if (instance.getLastHeartbeat().isBefore(cutoff)) {
                LOG.info("Removing stale instance: " + entry.getKey());
                notifyInstanceUnregistered(entry.getKey());
                return true;
              }
              return false;
            });
  }

  private void notifyInstanceRegistered(ServiceInstance instance) {
    for (ServiceRegistryChangeListener listener : listeners) {
      try {
        listener.onInstanceRegistered(instance);
      } catch (Exception e) {
        LOG.warning("Listener notification failed: " + e.getMessage());
      }
    }
  }

  private void notifyInstanceUnregistered(String instanceId) {
    for (ServiceRegistryChangeListener listener : listeners) {
      try {
        listener.onInstanceUnregistered(instanceId);
      } catch (Exception e) {
        LOG.warning("Listener notification failed: " + e.getMessage());
      }
    }
  }

  private void notifyInstanceUpdated(ServiceInstance instance) {
    for (ServiceRegistryChangeListener listener : listeners) {
      try {
        listener.onInstanceUpdated(instance);
      } catch (Exception e) {
        LOG.warning("Listener notification failed: " + e.getMessage());
      }
    }
  }
}
