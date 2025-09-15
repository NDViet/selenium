# Greedy Load Balancer

## Overview

The Greedy Load Balancer is a smart load balancing strategy that routes requests to Grid instances until they reach their configured capacity, then moves to the next available instance. This maximizes utilization of each instance before distributing load to others.

## Key Features

- **Default Strategy**: GREEDY is available as a load balancing strategy
- **Capacity-Based Routing**: Routes to instances until they reach configured capacity
- **YAML Configuration Support**: Configurable capacity per Grid instance
- **Graceful Fallbacks**: Robust error handling with sensible defaults
- **Health-Aware**: Only routes to healthy Grid instances

## Configuration

### YAML Configuration

```yaml
instances:
  - index: 0
    maxCapabilities: 200
  - index: 1
    maxCapabilities: 500
  - index: 2
    maxCapabilities: 300
```

### Configuration Behavior

| Scenario | Behavior |
|----------|----------|
| **Valid maxCapabilities (> 0)** | Uses configured value |
| **Invalid maxCapabilities (≤ 0)** | Falls back to 500 |
| **No YAML config** | Uses default 500 |
| **Out-of-bounds indices** | Ignored gracefully, uses default 500 |

## Implementation Details

### Core Classes

#### GridInstanceRegistry
- **Location**: `GridInstanceRegistry.java`
- **Strategy**: `LoadBalancingStrategy.GREEDY`
- **Method**: `selectGreedy(List<GridInstance> available)`

```java
private GridInstance selectGreedy(List<GridInstance> available) {
  // Sort instances by current session count (ascending)
  List<GridInstance> sorted = available.stream()
      .sorted(Comparator.comparingInt(GridInstance::getSessionCount))
      .collect(Collectors.toList());
  
  // Find first instance under capacity
  for (GridInstance instance : sorted) {
    int estimatedCapacity = getEstimatedCapacity(instance);
    if (instance.getSessionCount() < estimatedCapacity) {
      return instance;
    }
  }
  
  // Fallback to least loaded if all at capacity
  return sorted.get(0);
}
```

#### InstanceConfig
- **Location**: `InstanceConfig.java`
- **Purpose**: Represents YAML instance configuration
- **Fallback Logic**: Invalid values default to 500

```java
public InstanceConfig(int index, int maxCapabilities) {
  this.index = Require.nonNegative("Instance index", index);
  // Fallback to 500 for invalid values instead of breaking system
  this.maxCapabilities = maxCapabilities > 0 ? maxCapabilities : 500;
}
```

### Capacity Management

#### Default Capacity
- **Default Value**: 500 sessions per Grid instance
- **Fallback Scenarios**:
  - No YAML configuration provided
  - Invalid maxCapabilities (≤ 0)
  - Out-of-bounds instance indices

#### Capacity Resolution
```java
private int getEstimatedCapacity(GridInstance instance) {
  int instanceIndex = allInstances.indexOf(instance);
  return instanceCapacities.getOrDefault(instanceIndex, 500);
}
```

## Algorithm Flow

1. **Get Available Instances**: Filter healthy Grid instances
2. **Sort by Load**: Order by current session count (ascending)
3. **Check Capacity**: Find first instance under configured capacity
4. **Route Request**: Direct traffic to selected instance
5. **Fallback**: If all at capacity, use least loaded instance

## Health Integration

The Greedy Load Balancer integrates with the health checking system:

- **Health Status**: Only considers `HEALTHY` instances
- **Load Factor**: Unhealthy instances have `Double.MAX_VALUE` load factor
- **Availability**: Uses `isAvailableForNewSessions()` for filtering

## Error Handling

### Graceful Degradation
- **No Available Instances**: Returns empty Optional
- **Configuration Errors**: Falls back to default values
- **Out-of-Bounds Indices**: Ignored without breaking system
- **Invalid Capacities**: Automatically corrected to 500

### Logging
- **Selection**: Logs selected instance and capacity info
- **Fallbacks**: Warns when using fallback strategies
- **Errors**: Logs configuration and runtime errors

## Testing

### Test Coverage
- **Strategy Verification**: Confirms GREEDY is available
- **Capacity Logic**: Tests session count management
- **Health Integration**: Verifies health status filtering
- **Load Factor**: Tests load calculation logic
- **YAML Configuration**: Validates configuration parsing
- **Error Handling**: Tests all fallback scenarios

### Key Test Scenarios
1. **Default 500 Capacity**: When no YAML config provided
2. **Out-of-Bounds Indices**: Graceful handling without system failure
3. **Invalid MaxCapabilities**: Fallback to 500 instead of exceptions
4. **Health Status**: Only healthy instances selected
5. **Load Distribution**: Proper greedy algorithm behavior

## Usage Examples

### Basic Usage (No Configuration)
```java
GridInstanceRegistry registry = new GridInstanceRegistry(
    tracer, httpClientFactory, 
    LoadBalancingStrategy.GREEDY,  // Uses default 500 capacity
    healthCheckInterval, healthCheckTimeout, maxFailureCount
);
```

### With YAML Configuration
```java
RoutingRulesConfig config = RoutingRulesConfig.fromYamlFile(configPath);
GridInstanceRegistry registry = new GridInstanceRegistry(
    tracer, httpClientFactory, 
    LoadBalancingStrategy.GREEDY,
    healthCheckInterval, healthCheckTimeout, maxFailureCount,
    config  // Contains instance capacity configuration
);
```

## Performance Characteristics

- **Time Complexity**: O(n log n) for sorting + O(n) for capacity check
- **Space Complexity**: O(n) for instance list and capacity map
- **Throughput**: Maximizes utilization before load spreading
- **Latency**: Minimal overhead for instance selection

## Best Practices

1. **Capacity Planning**: Set realistic maxCapabilities based on instance resources
2. **Health Monitoring**: Ensure health checks are properly configured
3. **Configuration Validation**: Test YAML configuration before deployment
4. **Monitoring**: Track session distribution across instances
5. **Fallback Testing**: Verify system behavior with invalid configurations