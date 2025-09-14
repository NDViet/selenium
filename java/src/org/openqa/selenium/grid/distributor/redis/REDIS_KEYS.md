# Redis Key Structure for Distributor

This document describes the Redis key patterns used by the Redis-backed Distributor implementation.

## Key Patterns

### Node Data
- **Pattern**: `distributor:node:{nodeId}:status`
- **Type**: String (JSON)
- **Description**: Stores the complete NodeStatus object as JSON
- **Example**: `distributor:node:abc123:status`

- **Pattern**: `distributor:node:{nodeId}:slots`
- **Type**: String (JSON)
- **Description**: Stores the node's slots as JSON array
- **Example**: `distributor:node:abc123:slots`

### Node Availability Sets
- **Pattern**: `distributor:availability:{availability}`
- **Type**: Set
- **Description**: Redis sets containing node IDs grouped by availability status
- **Examples**: 
  - `distributor:availability:UP` - Set of UP node IDs
  - `distributor:availability:DOWN` - Set of DOWN node IDs
  - `distributor:availability:DRAINING` - Set of DRAINING node IDs

### Slot Reservations
- **Pattern**: `distributor:slot:{slotId}`
- **Type**: String
- **Description**: Stores session ID for reserved slots
- **Example**: `distributor:slot:node123-slot1` → `session456`

### Health Check Counters
- **Pattern**: `distributor:health:{nodeId}`
- **Type**: String (Integer)
- **Description**: Tracks consecutive health check failures
- **Example**: `distributor:health:abc123` → `2`

### Node Registry
- **Pattern**: `distributor:registry:{nodeId}`
- **Type**: String
- **Description**: Tracks active nodes in the registry
- **Example**: `distributor:registry:abc123` → `active`

## Operations

### Adding a Node
1. Store node status: `SET distributor:node:{nodeId}:status {nodeStatusJson}`
2. Store node slots: `SET distributor:node:{nodeId}:slots {slotsJson}`
3. Add to availability set: `SADD distributor:availability:DOWN {nodeId}`

### Updating Node Availability
1. Remove from old set: `SREM distributor:availability:{oldStatus} {nodeId}`
2. Add to new set: `SADD distributor:availability:{newStatus} {nodeId}`
3. Update node status: `SET distributor:node:{nodeId}:status {updatedNodeStatusJson}`

### Reserving a Slot
1. Check node availability and slot availability
2. Set reservation: `SET distributor:slot:{slotId} reserved`
3. Update node with reserved slot

### Releasing a Session
1. Find slot by pattern: `KEYS distributor:slot:*`
2. Check session ID match
3. Delete reservation: `DEL distributor:slot:{slotId}`
4. Update node slots

### Health Check Updates
- Increment failures: `INCR distributor:health:{nodeId}`
- Reset on success: `SET distributor:health:{nodeId} 0`

### Node Cleanup
1. Get node status: `GET distributor:node:{nodeId}:status`
2. Remove from availability set: `SREM distributor:availability:{status} {nodeId}`
3. Delete all node data: `DEL distributor:node:{nodeId}:status distributor:node:{nodeId}:slots distributor:health:{nodeId}`

## Benefits

1. **Distributed State**: Multiple distributor instances can share the same Redis backend
2. **Efficient Queries**: Availability sets allow fast filtering of nodes by status
3. **Atomic Operations**: Redis operations ensure consistency across concurrent access
4. **Scalability**: Redis clustering can handle large-scale deployments
5. **Persistence**: Node state survives distributor restarts