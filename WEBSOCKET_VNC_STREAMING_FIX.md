# WebSocket VNC Streaming Connection Fix

## Issue Description

The VNC WebSocket connection `ws://192.168.2.48:9996/session/f7f79e4e991ef34beb323d9c00d61337/se/vnc` is finishing immediately instead of staying alive for continuous data streaming. VNC connections require persistent WebSocket connections to stream real-time screen data continuously.

## Root Cause Analysis

The issue occurs because:

1. **Connection Timeout**: Default WebSocket timeouts are too short for streaming connections
2. **Premature Connection Closure**: The WebSocket connection is being closed by either the Load Balancer or the Grid instance
3. **Session State Issues**: The session might not be properly active or mapped
4. **Grid Instance Health**: The target Grid instance might not be healthy or accessible

## Solution Implemented

### 1. Extended Connection Timeouts

```java
// Create HTTP client with longer timeout for streaming connections like VNC
ClientConfig config = ClientConfig.defaultConfig()
    .baseUri(sessionUri)
    .readTimeout(Duration.ofMinutes(30)) // Long timeout for streaming connections
    .connectionTimeout(Duration.ofSeconds(30));
```

**Benefits:**
- Prevents premature timeout for long-running VNC sessions
- Allows continuous data streaming without connection drops
- Configurable timeout values for different environments

### 2. Enhanced Connection Lifecycle Management

```java
// Verify the Grid instance is healthy before attempting WebSocket connection
if (!instance.isHealthy()) {
    LOG.warning("Grid instance " + instance.getId() + " is not healthy - cannot establish WebSocket connection for session " + sessionId.get());
    return Optional.empty();
}
```

**Benefits:**
- Prevents connection attempts to unhealthy Grid instances
- Provides clear error messages for debugging
- Ensures connection reliability

### 3. Comprehensive Debugging and Logging

```java
// For VNC connections, log that we expect continuous data streaming
if (uri.contains("/se/vnc")) {
    LOG.info("VNC WebSocket connection established - expecting continuous binary data stream for session " + sessionId.get());
}
```

**Enhanced ForwardingListener logging:**
```java
@Override
public void onClose(int code, String reason) {
    LOG.warning("CRITICAL: WebSocket connection closed by Grid instance " + gridInstanceId + 
                " for session " + sessionId + " with code " + code + " and reason: " + reason + 
                ". This may indicate a problem with the upstream Grid instance or session state.");
    downstream.accept(new CloseMessage(code, reason));
}
```

**Benefits:**
- Detailed logging for WebSocket connection lifecycle
- Clear identification of VNC streaming connections
- Critical alerts when connections close unexpectedly
- Binary data transfer logging for VNC streams

## VNC WebSocket Connection Flow

```
Client VNC Request: ws://load-balancer:9996/session/{sessionId}/se/vnc
    ↓
Load Balancer WebSocket Proxy
    ↓
Extract Session ID: f7f79e4e991ef34beb323d9c00d61337
    ↓
Look up Grid Instance via SessionGridMapping
    ↓
Verify Grid Instance Health
    ↓
Create WebSocket connection with extended timeouts
    ↓
Establish upstream connection: ws://grid-instance:4444/session/{sessionId}/se/vnc
    ↓
Continuous bidirectional binary data streaming (VNC screen data)
    ↓
Connection remains open until explicitly closed
```

## Debugging Steps

### 1. Check Load Balancer Logs

```bash
# Look for WebSocket proxy logs
grep "WebSocket proxy received request" load-balancer.log

# Check session mapping
grep "session.*f7f79e4e991ef34beb323d9c00d61337" load-balancer.log

# Check VNC connection establishment
grep "VNC WebSocket connection established" load-balancer.log

# Check for connection closures
grep "CRITICAL: WebSocket connection closed" load-balancer.log
```

### 2. Verify Session State

```bash
# Check if session exists and is active
curl http://192.168.2.48:9996/status

# Check session mapping in Load Balancer
curl http://192.168.2.48:9996/graphql -d '{"query": "{ allSessions { id } }"}'
```

### 3. Test Grid Instance Health

```bash
# Check Grid instance health
curl http://grid-instance:4444/status

# Test direct VNC connection to Grid instance
wscat -c "ws://grid-instance:4444/session/f7f79e4e991ef34beb323d9c00d61337/se/vnc"
```

## Common Issues and Solutions

### Issue 1: Session Not Found
**Symptoms:** `Session not found for WebSocket uri` in logs
**Solution:** 
- Verify session was created through the Load Balancer
- Check session ID format in WebSocket URI
- Ensure session is still active

### Issue 2: Grid Instance Not Healthy
**Symptoms:** `Grid instance X is not healthy` in logs
**Solution:**
- Check Grid instance status and connectivity
- Restart unhealthy Grid instances
- Verify network connectivity between Load Balancer and Grid instances

### Issue 3: Connection Closes Immediately
**Symptoms:** `CRITICAL: WebSocket connection closed` immediately after establishment
**Solution:**
- Check Grid instance VNC endpoint availability
- Verify session has an active browser with VNC enabled
- Check for firewall or network issues

### Issue 4: No Binary Data Received
**Symptoms:** VNC connection established but no screen data
**Solution:**
- Verify browser session has VNC capabilities enabled
- Check if Grid node has VNC server running
- Ensure proper VNC configuration in Grid node

## Testing VNC WebSocket Connection

### Manual Test Script

```bash
#!/bin/bash
# Test VNC WebSocket connection through Load Balancer

SESSION_ID="f7f79e4e991ef34beb323d9c00d61337"
LOAD_BALANCER="192.168.2.48:9996"

echo "Testing VNC WebSocket connection..."
echo "Session ID: $SESSION_ID"
echo "Load Balancer: $LOAD_BALANCER"

# Test WebSocket connection
wscat -c "ws://$LOAD_BALANCER/session/$SESSION_ID/se/vnc" \
  --timeout 60 \
  --wait-for-pong 30
```

### Expected Behavior

1. **Connection Establishment**: WebSocket connection should establish successfully
2. **Continuous Data Stream**: Binary data should flow continuously for VNC screen updates
3. **Connection Persistence**: Connection should remain open until explicitly closed
4. **Error Handling**: Clear error messages if connection fails

## Performance Considerations

- **Memory Usage**: VNC streaming uses minimal memory for message forwarding
- **CPU Usage**: Low CPU overhead for binary data forwarding
- **Network Bandwidth**: Depends on VNC screen update frequency and resolution
- **Connection Limits**: Each VNC session uses one persistent WebSocket connection

## Security Considerations

- **Session Validation**: Only routes VNC connections for valid, mapped sessions
- **Grid Instance Validation**: Only routes to healthy, registered Grid instances
- **Connection Isolation**: Each session's VNC stream is isolated and secure

## Future Enhancements

1. **VNC Connection Pooling**: Reuse VNC connections for better performance
2. **Bandwidth Optimization**: Compress VNC data streams
3. **Connection Health Monitoring**: Monitor VNC connection quality
4. **Automatic Reconnection**: Reconnect dropped VNC connections automatically

## Conclusion

The enhanced WebSocket proxy now properly handles VNC streaming connections with:
- Extended timeouts for continuous data streaming
- Comprehensive health checks and error handling
- Detailed logging for debugging connection issues
- Proper connection lifecycle management

This ensures that VNC WebSocket connections remain alive for continuous screen data streaming rather than finishing immediately.
