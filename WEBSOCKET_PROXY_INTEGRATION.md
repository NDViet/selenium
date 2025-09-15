# WebSocket Proxy Integration for Selenium Grid Load Balancer

## Overview

The Selenium Grid Load Balancer now includes robust WebSocket proxy support that enables transparent bidirectional WebSocket communication between clients and Grid instances. This feature ensures that WebSocket connections (such as VNC, CDP, and other debugging protocols) are properly routed to the correct Grid instance based on session affinity.

## Architecture

### Components

1. **ProxyWebsocketsIntoLoadBalancer**: Main WebSocket proxy class that handles routing
2. **SessionGridMapping**: Maps session IDs to Grid instance IDs
3. **GridInstanceRegistry**: Manages Grid instance information and health
4. **HttpClient.Factory**: Creates HTTP clients for WebSocket connections

### WebSocket Routing Flow

```
Client WebSocket Request
    ↓
Load Balancer WebSocket Handler
    ↓
Extract Session ID from URI
    ↓
Look up Grid Instance via SessionGridMapping
    ↓
Establish WebSocket connection to Grid Instance
    ↓
Bidirectional message forwarding
```

## Implementation Details

### ProxyWebsocketsIntoLoadBalancer Class

```java
public class ProxyWebsocketsIntoLoadBalancer 
    implements BiFunction<String, Consumer<Message>, Optional<Consumer<Message>>> {
    
    private final HttpClient.Factory clientFactory;
    private final SessionGridMapping sessionGridMapping;
    private final GridInstanceRegistry gridInstanceRegistry;
    
    @Override
    public Optional<Consumer<Message>> apply(String uri, Consumer<Message> downstream) {
        // Extract session ID from WebSocket URI
        Optional<SessionId> sessionId = HttpSessionId.getSessionId(uri).map(SessionId::new);
        
        // Look up Grid instance hosting this session
        Optional<String> gridInstanceId = sessionGridMapping.getGridInstanceId(sessionId.get());
        
        // Get GridInstance object from registry
        Optional<GridInstance> gridInstance = gridInstanceRegistry.getGridInstance(gridInstanceId.get());
        
        // Create WebSocket connection to Grid instance
        // Forward messages bidirectionally
    }
}
```

### Integration with GridLoadBalancerCommand

The WebSocket proxy is integrated into the Load Balancer command structure:

```java
// Create WebSocket proxy for Load Balancer
ProxyWebsocketsIntoLoadBalancer websocketProxy = new ProxyWebsocketsIntoLoadBalancer(
    loadBalancer.getHttpClientFactory(), 
    loadBalancer.getSessionGridMapping(),
    loadBalancer.getGridInstanceRegistry());

// Pass to handlers
return new GridLoadBalancerHandlers(httpHandler, loadBalancer, websocketProxy);
```

## Supported WebSocket Protocols

The WebSocket proxy supports all Selenium Grid WebSocket protocols:

- **VNC**: `/session/{sessionId}/se/vnc` - Virtual Network Computing for visual debugging
- **CDP**: `/session/{sessionId}/se/cdp` - Chrome DevTools Protocol
- **BiDi**: `/session/{sessionId}/se/bidi` - WebDriver BiDirectional Protocol
- **Custom protocols**: Any session-based WebSocket endpoint

## Session Affinity

WebSocket connections are routed based on session affinity:

1. **Session ID Extraction**: The proxy extracts the session ID from the WebSocket URI using `HttpSessionId.getSessionId()`
2. **Grid Instance Lookup**: Uses `SessionGridMapping.getGridInstanceId()` to find which Grid instance hosts the session
3. **Connection Routing**: Establishes WebSocket connection to the appropriate Grid instance
4. **Message Forwarding**: Forwards all WebSocket messages bidirectionally between client and Grid instance

## Error Handling

The WebSocket proxy includes comprehensive error handling:

- **Session Not Found**: Returns empty Optional if session ID cannot be extracted
- **Grid Instance Not Found**: Logs warning and returns empty Optional if Grid instance mapping is missing
- **Connection Failures**: Logs errors and closes connections gracefully
- **Message Forwarding Errors**: Handles WebSocket message forwarding failures with logging

## Debugging and Logging

Comprehensive logging is included for debugging:

```java
LOG.info("Load Balancer WebSocket proxy received request for URI: " + uri);
LOG.info("Extracted session ID: " + sessionId.get() + " from WebSocket URI: " + uri);
LOG.info("Found Grid instance " + gridInstance.getId() + " for session " + sessionId.get());
```

## Configuration

No additional configuration is required. The WebSocket proxy uses the same configuration as the Load Balancer:

- **Timeouts**: Uses Load Balancer request and connection timeouts
- **Grid Instances**: Uses the same Grid instance registry and health checking
- **Session Mapping**: Uses the same session-to-Grid mapping as HTTP requests

## Testing

### Manual Testing

1. **Start Load Balancer**: Ensure Load Balancer is running with multiple Grid instances
2. **Create Session**: Create a WebDriver session through the Load Balancer
3. **WebSocket Connection**: Connect to WebSocket endpoint (e.g., VNC)
   ```
   ws://load-balancer:4444/session/{sessionId}/se/vnc
   ```
4. **Verify Routing**: Check logs to confirm session is routed to correct Grid instance

### Integration Testing

```bash
# Test WebSocket connection
wscat -c "ws://localhost:4444/session/abc123/se/vnc"

# Check Load Balancer logs for routing information
tail -f load-balancer.log | grep "WebSocket"
```

## Performance Considerations

- **Connection Pooling**: WebSocket connections are created on-demand and managed efficiently
- **Memory Usage**: Minimal memory overhead for WebSocket message forwarding
- **Latency**: Low latency forwarding with direct message passing
- **Scalability**: Supports multiple concurrent WebSocket connections per session

## Security

- **Session Validation**: Only routes WebSocket connections for valid, mapped sessions
- **Grid Instance Validation**: Only routes to healthy, registered Grid instances
- **Error Information**: Doesn't expose internal Grid instance details in error messages

## Troubleshooting

### Common Issues

1. **WebSocket Connection Refused**
   - Check if session exists and is mapped to a Grid instance
   - Verify Grid instance is healthy and accessible
   - Check Load Balancer logs for routing information

2. **Session Not Found**
   - Ensure session was created through the Load Balancer
   - Check session mapping in Load Balancer logs
   - Verify session ID format in WebSocket URI

3. **Grid Instance Unreachable**
   - Check Grid instance health status
   - Verify network connectivity between Load Balancer and Grid instance
   - Check Grid instance WebSocket endpoint availability

### Debug Commands

```bash
# Check Load Balancer WebSocket proxy logs
grep "WebSocket proxy" load-balancer.log

# Check session mappings
grep "session.*mapping" load-balancer.log

# Check Grid instance health
curl http://load-balancer:4444/status
```

## Future Enhancements

- **WebSocket Connection Pooling**: Reuse WebSocket connections for better performance
- **Protocol-Specific Routing**: Route different WebSocket protocols to specialized Grid instances
- **WebSocket Health Checks**: Monitor WebSocket endpoint health on Grid instances
- **Load Balancing**: Distribute WebSocket connections across multiple Grid instances for the same session

## Conclusion

The WebSocket proxy integration provides seamless WebSocket support for the Selenium Grid Load Balancer, enabling transparent routing of debugging and automation protocols across multiple Grid instances while maintaining session affinity and providing robust error handling.
