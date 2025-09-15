# Health Checking

## Overview

The Health Checking system monitors Grid instance availability and health status at configurable intervals. It ensures that only healthy instances receive traffic and provides automatic failover when instances become unavailable.

## Features

- **Periodic Health Checks**: Configurable interval-based monitoring
- **Health Status Tracking**: HEALTHY, UNHEALTHY, DRAINING states
- **Automatic Failover**: Unhealthy instances excluded from load balancing
- **Session Count Updates**: Real-time session tracking via distributor status
- **Failure Threshold**: Configurable failure count before marking unhealthy

## Health Check Endpoint

### GET /status/{instanceId}

Returns the health status of a specific Grid instance.

**Request:**
```http
GET /status/grid-instance-1
```

**Response (Healthy):**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "grid-instance-1",
  "baseUri": "http://grid1:4444",
  "status": "HEALTHY",
  "sessionCount": 5,
  "lastHealthCheck": "2024-01-15T10:30:45.123Z"
}
```

**Response (Not Found):**
```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{
  "error": "Grid instance not found: invalid-instance-id"
}
```

## Implementation

### Core Classes

#### GridInstanceRegistry
- **Health Check Executor**: Scheduled thread pool for periodic checks
- **Health Check Method**: `performHealthCheck(GridInstance instance)`
- **Failure Handling**: `handleHealthCheckFailure(GridInstance instance, String reason)`

#### GridInstance
- **Status Management**: HEALTHY, UNHEALTHY, DRAINING states
- **Session Tracking**: Current session count and updates
- **Failure Counting**: Tracks consecutive failures
- **Availability**: `isAvailableForNewSessions()` method

### Health Check Process

#### Periodic Execution
```java
healthCheckExecutor.scheduleAtFixedRate(
    healthCheckTask,
    0, // Start immediately
    healthCheckInterval.toSeconds(), // Configurable interval
    TimeUnit.SECONDS
);
```

#### Health Check Logic
1. **HTTP Request**: GET `/se/grid/distributor/status` on Grid instance
2. **Status Validation**: Check for HTTP 200 response
3. **Session Count Update**: Parse distributor status for active sessions
4. **Health Status Update**: Mark as HEALTHY or handle failure
5. **Failure Tracking**: Increment failure count on errors

#### Failure Handling
```java
private void handleHealthCheckFailure(GridInstance instance, String reason) {
  instance.incrementFailureCount();
  
  if (instance.getFailureCount() >= maxFailureCount) {
    if (instance.isHealthy()) {
      instance.setStatus(GridInstance.Status.UNHEALTHY);
      LOG.warning("Grid instance marked as unhealthy: " + reason);
    }
  }
}
```

## Configuration

### Health Check Parameters
- **Health Check Interval**: Time between health checks (e.g., 30 seconds)
- **Health Check Timeout**: HTTP request timeout (e.g., 5 seconds)
- **Max Failure Count**: Failures before marking unhealthy (e.g., 3)

### Constructor Configuration
```java
public GridInstanceRegistry(
    Tracer tracer,
    HttpClient.Factory httpClientFactory,
    LoadBalancingStrategy strategy,
    Duration healthCheckInterval,    // 30 seconds
    Duration healthCheckTimeout,     // 5 seconds
    int maxFailureCount             // 3 failures
) {
  // ... initialization
}
```

## Health States

### HEALTHY
- **Condition**: Recent successful health check
- **Behavior**: Available for new sessions
- **Load Factor**: Based on session count and failures
- **Routing**: Included in load balancing

### UNHEALTHY
- **Condition**: Exceeded maximum failure count
- **Behavior**: Not available for new sessions
- **Load Factor**: `Double.MAX_VALUE` (excluded from selection)
- **Routing**: Excluded from load balancing

### DRAINING
- **Condition**: Manually set for maintenance
- **Behavior**: Not available for new sessions
- **Load Factor**: Based on current load
- **Routing**: Excluded from new session routing

## Session Tracking

### Distributor Status Integration
```java
// Parse distributor status response
DistributorStatus status = json.toType(content, DistributorStatus.class);

// Calculate total active sessions
int totalSessions = status.getNodes().stream()
    .mapToInt(nodeStatus -> {
      return (int) nodeStatus.getSlots().stream()
          .filter(slot -> slot.getSession() != null)
          .count();
    })
    .sum();

instance.setSessionCount(totalSessions);
```

### Session Count Updates
- **Health Check**: Updates session count from distributor status
- **Session Creation**: `onSessionCreated(gridInstanceId)`
- **Session Termination**: `onSessionEnded(gridInstanceId)`

## Load Factor Calculation

### Healthy Instance
```java
public double getLoadFactor() {
  if (!isHealthy()) {
    return Double.MAX_VALUE;
  }
  // Base load + failure penalty
  return sessionCount.get() + (failureCount.get() * 0.1);
}
```

### Load Factor Components
- **Session Count**: Primary load indicator
- **Failure Count**: Penalty for recent failures (0.1 per failure)
- **Health Status**: Unhealthy instances get maximum load factor

## Integration with Load Balancing

### Instance Selection
```java
public List<GridInstance> getAvailableGridInstances() {
  return instances.values().stream()
      .filter(GridInstance::isAvailableForNewSessions) // Health check integration
      .collect(Collectors.toList());
}
```

### Health-Aware Routing
- **Greedy Strategy**: Only considers healthy instances
- **Round Robin**: Skips unhealthy instances
- **Least Sessions**: Excludes unhealthy from comparison
- **Least Load**: Uses load factor (unhealthy = max load)

## Monitoring and Logging

### Health Check Events
- **Success**: Instance marked healthy, session count updated
- **Failure**: Failure count incremented, reason logged
- **Status Change**: Health status transitions logged
- **Recovery**: Instance recovery from unhealthy state

### Log Levels
- **INFO**: Health status changes, instance recovery
- **WARNING**: Health check failures, unhealthy instances
- **FINE**: Individual health check results, session updates

## Error Scenarios

### Network Issues
- **Connection Timeout**: Counted as failure
- **Connection Refused**: Counted as failure
- **DNS Resolution**: Counted as failure

### HTTP Errors
- **4xx Responses**: Counted as failure
- **5xx Responses**: Counted as failure
- **Non-200 Status**: Counted as failure

### Parsing Errors
- **Invalid JSON**: Logged but not counted as failure
- **Missing Fields**: Session count not updated
- **Format Changes**: Graceful degradation

## Best Practices

1. **Interval Tuning**: Balance responsiveness vs. resource usage
2. **Timeout Configuration**: Set appropriate timeouts for network conditions
3. **Failure Threshold**: Configure based on expected reliability
4. **Monitoring**: Track health check success rates
5. **Alerting**: Alert on persistent health check failures
6. **Recovery Testing**: Test instance recovery scenarios
7. **Resource Management**: Monitor health check thread pool usage

## Testing

### Test Coverage
- **Health Status Transitions**: HEALTHY ↔ UNHEALTHY ↔ DRAINING
- **Failure Counting**: Increment/reset failure counts
- **Session Tracking**: Session count updates
- **Load Factor**: Health impact on load calculation
- **Availability**: Health impact on instance selection

### Integration Testing
- **End-to-End**: Full health check cycle testing
- **Network Simulation**: Timeout and failure scenarios
- **Load Testing**: Health check performance under load
- **Recovery Testing**: Instance recovery validation