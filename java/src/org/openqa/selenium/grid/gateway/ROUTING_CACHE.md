# Routing Cache for Concurrent Session Requests

## Overview

The Routing Cache mechanism prevents multiple concurrent session requests from being routed to the same Grid instance while a session is being created. This ensures better distribution of concurrent requests and avoids overloading specific instances.

## Problem Solved

**Scenario**: 3 concurrent requests for `browserName=chrome` arrive simultaneously:
- Without routing cache: All 3 requests might route to the same Grid instance
- With routing cache: Each request routes to a different available instance

## How It Works

### Request Flow
1. **Request 1**: Routes to Instance A, marks Instance A as "pending"
2. **Request 2**: Skips Instance A (pending), routes to Instance B, marks Instance B as "pending"  
3. **Request 3**: Skips Instances A & B (pending), routes to Instance C, marks Instance C as "pending"

### Cache Management
- **Mark Pending**: When instance is selected for session creation
- **Clear Pending**: When session creation completes (success or failure)
- **Timeout**: Pending markers expire after 30 seconds (configurable)

## Implementation

### RoutingCache Class
```java
public class RoutingCache {
  // Mark instance as having pending session creation
  public boolean markPending(String instanceId);
  
  // Clear pending marker after session creation
  public void clearPending(String instanceId);
  
  // Check if instance has pending request
  public boolean isPending(String instanceId);
}
```

### Enhanced Load Balancers
Both `RoundRobinLoadBalancer` and `LeastSessionsLoadBalancer` now:
1. Filter out instances with pending requests
2. Mark selected instance as pending
3. Provide `clearPending()` method for cleanup

### Integration Points
- **GridInstanceRegistry**: Automatically clears pending markers on session creation/failure
- **Load Balancers**: Use routing cache during instance selection
- **Fallback Behavior**: If all instances are pending, falls back to normal selection

## Configuration

### Default Settings
- **Cache Timeout**: 30 seconds
- **Automatic Cleanup**: Expired entries removed during operations
- **Thread Safety**: Concurrent access supported

### Custom Configuration
```java
// Custom timeout
RoutingCache cache = new RoutingCache(Duration.ofSeconds(60));

// Use with load balancer
RoundRobinLoadBalancer balancer = new RoundRobinLoadBalancer(capabilityMatcher, cache);
```

## Benefits

1. **Better Distribution**: Concurrent requests spread across available instances
2. **Reduced Contention**: Prevents multiple requests competing for same instance
3. **Improved Performance**: More efficient resource utilization
4. **Automatic Cleanup**: Self-managing cache with timeout-based expiration

## Example Scenario

**3 Chrome Requests with 3 Chrome-capable Instances**:

| Request | Without Cache | With Cache |
|---------|---------------|------------|
| Request 1 | Instance 2 | Instance 2 (marked pending) |
| Request 2 | Instance 2 | Instance 1 (marked pending) |
| Request 3 | Instance 2 | Instance 3 (marked pending) |

**Result**: Perfect distribution instead of all requests going to same instance.

## Testing

### Test Coverage
- **Basic Operations**: Mark/clear/check pending status
- **Concurrent Requests**: Multiple requests select different instances
- **Fallback Behavior**: Works when all instances are pending
- **Cache Cleanup**: Automatic clearing on session creation/failure
- **Timeout Handling**: Expired entries are cleaned up

### Verification
```java
@Test
void testConcurrentRequestDistribution() {
  // 3 requests should select 3 different instances
  Set<String> selectedIds = new HashSet<>();
  for (int i = 0; i < 3; i++) {
    Optional<GridInstance> selected = balancer.selectGridInstance(instances, capabilities);
    selectedIds.add(selected.get().getId());
  }
  assertEquals(3, selectedIds.size()); // All different instances
}
```

## Integration

The routing cache is automatically integrated into existing load balancing strategies:
- **No Configuration Required**: Works out of the box
- **Backward Compatible**: Existing behavior preserved when cache is empty
- **Transparent Operation**: No changes needed to existing Grid setup

This enhancement significantly improves concurrent session handling while maintaining full compatibility with existing Grid deployments.