# Dynamic Grid Instance Discovery

This package provides dynamic service discovery capabilities for Selenium Grid, allowing new Grid instances to be automatically discovered by load balancers, similar to how API Gateways work.

## Key Components

### 1. ServiceRegistry Interface
- Defines the contract for service registration and discovery
- Supports registration, unregistration, heartbeats, and change notifications
- Can be implemented with various backends (Consul, etcd, Kubernetes, etc.)

### 2. DynamicGridInstanceRegistry
- Extends the existing GridInstanceRegistry
- Automatically discovers new Grid instances via ServiceRegistry
- Handles instance lifecycle (registration, health checking, removal)

### 3. RouterRegistration
- Allows Grid routers to register themselves with service discovery
- Handles heartbeats and graceful unregistration
- Integrates with existing router startup process

## Usage Examples

### Starting a Discoverable Router

```bash
# Router registers itself with load balancer
java -jar selenium-server.jar discoverable-router \
  --service-registry-url http://loadbalancer:8080/registry \
  --heartbeat-interval 30
```

### Starting a Dynamic Load Balancer

```bash
# Load balancer automatically discovers Grid instances
java -cp selenium-server.jar org.openqa.selenium.grid.loadbalancer.example.DynamicLoadBalancer
```

## Configuration Options

### Router Configuration
- `service-registry-url`: URL of the service registry endpoint
- `heartbeat-interval`: Interval for sending heartbeats (seconds)

### Load Balancer Configuration
- `discovery-interval`: How often to check for new instances (seconds)
- `instance-timeout`: How long before considering an instance stale (seconds)

## Implementation Strategies

### 1. HTTP-Based Registry (Simple)
- In-memory storage with REST API
- Good for small deployments
- No external dependencies

### 2. Consul Integration
```java
public class ConsulServiceRegistry implements ServiceRegistry {
    // Implementation using Consul API
}
```

### 3. Kubernetes Integration
```java
public class KubernetesServiceRegistry implements ServiceRegistry {
    // Implementation using Kubernetes Service Discovery
}
```

### 4. etcd Integration
```java
public class EtcdServiceRegistry implements ServiceRegistry {
    // Implementation using etcd API
}
```

## Benefits

1. **Automatic Discovery**: New Grid instances are automatically discovered
2. **Elastic Scaling**: Add/remove Grid instances without load balancer reconfiguration
3. **Health Monitoring**: Automatic removal of unhealthy instances
4. **Zero Downtime**: Rolling updates without service interruption
5. **API Gateway Pattern**: Similar to modern microservice architectures

## Integration with Existing Code

The dynamic discovery system extends the existing Selenium Grid architecture without breaking changes:

- `GridInstanceRegistry` → `DynamicGridInstanceRegistry`
- `RouterServer` → `DiscoverableRouterServer`
- Existing load balancing strategies remain unchanged
- Health checking and session affinity work as before