# Capability-Aware Load Balancing

The Gateway Service now includes capability-aware load balancing for Round Robin and Least Sessions strategies. This enhancement ensures that session requests are routed to Grid instances that have available slots matching the requested capabilities.

## How It Works

### Capability Matching Process

1. **Primary Selection**: When a session request arrives with specific capabilities (e.g., `browserName: chrome`), the load balancer first filters Grid instances to find those with available slots that match the requested capabilities.

2. **Fallback Behavior**: If no Grid instances have matching available slots, the load balancer falls back to the original strategy behavior, distributing across all available instances.

3. **Slot Matching**: The system reuses the existing `SlotMatcher` logic from the Distributor role, specifically the `DefaultSlotMatcher` implementation that handles:
   - Browser name, version, and platform matching
   - Extension capability matching
   - Managed downloads support
   - Platform version compatibility

### Enhanced Strategies

#### Round Robin with Capability Awareness
- **Primary**: Distributes requests evenly across Grid instances with matching available slots
- **Fallback**: Uses standard round robin across all instances if no capability matches

#### Least Sessions with Capability Awareness  
- **Primary**: Routes to the Grid instance with fewest sessions among those with matching available slots
- **Fallback**: Routes to the instance with fewest sessions overall if no capability matches

## Implementation Details

### CapabilityMatcher Class
- Reuses existing `SlotMatcher` interface and `DefaultSlotMatcher` implementation
- Queries Grid instance `/se/grid/distributor/status` endpoints to get node and slot information
- Filters instances based on available slots with matching stereotypes
- Includes timeout and error handling with graceful fallback

### Integration Points
- Enhanced `RoundRobinLoadBalancer` and `LeastSessionsLoadBalancer` classes
- Backward compatible - no configuration changes required
- Automatic capability matching when HTTP client factory is available
- Graceful degradation when capability matching is not possible

## Benefits

1. **Improved Session Success Rate**: Requests are routed to instances that can actually fulfill them
2. **Better Resource Utilization**: Avoids sending requests to instances without matching capabilities
3. **Seamless Fallback**: Maintains availability even when capability matching fails
4. **No Breaking Changes**: Existing configurations continue to work unchanged

## Configuration

No additional configuration is required. The capability-aware behavior is automatically enabled when:
- Session requests include capability requirements
- HTTP client factory is available for querying Grid instance status
- Grid instances are healthy and responding to status requests

The system gracefully falls back to original load balancing behavior in all other cases.