package org.openqa.selenium.grid.gateway.discovery;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.openqa.selenium.grid.gateway.GridInstance;
import org.openqa.selenium.grid.gateway.GridInstanceRegistry;
import org.openqa.selenium.grid.gateway.LoadBalancerFactory;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

/** Extended GridInstanceRegistry that supports dynamic discovery via ServiceRegistry. */
public class DynamicGridInstanceRegistry extends GridInstanceRegistry
    implements ServiceRegistry.ServiceRegistryChangeListener {

  private static final Logger LOG = Logger.getLogger(DynamicGridInstanceRegistry.class.getName());

  private final ServiceRegistry serviceRegistry;
  private final ConcurrentMap<String, GridInstance> dynamicInstances;
  private final ScheduledExecutorService discoveryExecutor;
  private final Duration discoveryInterval;

  public DynamicGridInstanceRegistry(
      Tracer tracer,
      HttpClient.Factory httpClientFactory,
      LoadBalancerFactory.StrategyType strategyType,
      Duration healthCheckInterval,
      Duration healthCheckTimeout,
      int maxFailureCount,
      ServiceRegistry serviceRegistry,
      Duration discoveryInterval) {

    super(
        tracer,
        httpClientFactory,
        strategyType,
        healthCheckInterval,
        healthCheckTimeout,
        maxFailureCount);

    this.serviceRegistry = serviceRegistry;
    this.dynamicInstances = new ConcurrentHashMap<>();
    this.discoveryInterval = discoveryInterval;

    this.discoveryExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r);
              thread.setDaemon(true);
              thread.setName("DynamicGridInstanceRegistry - Discovery");
              return thread;
            });

    // Register for service registry changes
    serviceRegistry.addChangeListener(this);

    // Start periodic discovery
    startPeriodicDiscovery();

    // Initial discovery
    performDiscovery();
  }

  private void startPeriodicDiscovery() {
    discoveryExecutor.scheduleAtFixedRate(
        this::performDiscovery,
        discoveryInterval.toSeconds(),
        discoveryInterval.toSeconds(),
        TimeUnit.SECONDS);
  }

  private void performDiscovery() {
    try {
      serviceRegistry
          .getInstances()
          .forEach(
              (instanceId, serviceInstance) -> {
                if (!dynamicInstances.containsKey(instanceId)) {
                  addDynamicGridInstance(instanceId, serviceInstance.getBaseUri());
                }
              });
    } catch (Exception e) {
      LOG.warning("Discovery failed: " + e.getMessage());
    }
  }

  private void addDynamicGridInstance(String instanceId, URI baseUri) {
    GridInstance gridInstance = new GridInstance(instanceId, baseUri);
    dynamicInstances.put(instanceId, gridInstance);
    super.addGridInstance(instanceId, baseUri);
    LOG.info("Dynamically discovered Grid instance: " + instanceId + " at " + baseUri);
  }

  @Override
  public void onInstanceRegistered(ServiceRegistry.ServiceInstance instance) {
    addDynamicGridInstance(instance.getInstanceId(), instance.getBaseUri());
  }

  @Override
  public void onInstanceUnregistered(String instanceId) {
    if (dynamicInstances.remove(instanceId) != null) {
      super.removeGridInstance(instanceId);
      LOG.info("Removed dynamically discovered Grid instance: " + instanceId);
    }
  }

  @Override
  public void onInstanceUpdated(ServiceRegistry.ServiceInstance instance) {
    // Handle instance updates if needed
  }

  @Override
  public void close() {
    serviceRegistry.removeChangeListener(this);
    discoveryExecutor.shutdown();
    super.close();
  }
}
