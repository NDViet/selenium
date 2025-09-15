# Discovery Endpoints

## Overview

The Discovery Endpoints provide dynamic Grid instance registration and unregistration capabilities for the Gateway. These endpoints allow Grid instances to be added or removed from the load balancer at runtime without requiring configuration changes.

## Endpoints

### POST /discovery (Register)

Registers a new Grid instance with the Gateway.

**Request:**
```http
POST /discovery
Content-Type: application/json

{
  "url": "http://grid1:4444"
}
```

**Response (Success):**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "status": "registered",
  "url": "http://grid1:4444",
  "instanceId": "gateway-discovered-1634567890123",
  "message": "Grid instance registered successfully"
}
```

**Response (Error):**
```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "error": "URL is required for registration"
}
```

### DELETE /discovery (Unregister)

Unregisters a Grid instance from the Gateway.

**Request (Query Parameter):**
```http
DELETE /discovery?url=http://grid1:4444
```

**Request (JSON Body):**
```http
DELETE /discovery
Content-Type: application/json

{
  "url": "http://grid1:4444"
}
```

**Response (Success):**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "status": "unregistered",
  "url": "http://grid1:4444",
  "instanceId": "gateway-discovered-1634567890123",
  "message": "Grid instance unregistered successfully"
}
```

**Response (Not Found):**
```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{
  "error": "Grid instance not found",
  "url": "http://nonexistent:4444",
  "message": "No Grid instance found with the specified URL"
}
```

## Implementation

### Core Classes

#### DiscoveryRegistrationHandler
- **Location**: `Gateway.java` (inner class)
- **Purpose**: Handles POST /discovery requests
- **Features**: URL validation, instance ID generation, registry integration

#### DiscoveryUnregistrationHandler
- **Location**: `Gateway.java` (inner class)
- **Purpose**: Handles DELETE /discovery requests
- **Features**: URL extraction, instance lookup, graceful removal

### Request Processing

#### Registration Flow
1. **Parse Request**: Extract URL from JSON payload
2. **Validate URL**: Ensure URL is provided and valid
3. **Generate ID**: Create unique instance ID with timestamp
4. **Register Instance**: Add to GridInstanceRegistry
5. **Return Response**: Confirm registration with instance details

#### Unregistration Flow
1. **Extract URL**: From query parameter or JSON body
2. **Find Instance**: Lookup by URL in registry
3. **Remove Instance**: Delete from GridInstanceRegistry
4. **Return Response**: Confirm removal with instance details

### Error Handling

| Error Scenario | HTTP Status | Response |
|---------------|-------------|----------|
| Missing URL | 400 | `{"error": "URL is required for registration"}` |
| Invalid JSON | 400 | `{"error": "Invalid registration data"}` |
| Registration Failure | 500 | `{"error": "Failed to register Grid instance"}` |
| Instance Not Found | 404 | `{"error": "Grid instance not found"}` |
| Unregistration Failure | 500 | `{"error": "Failed to unregister Grid instance"}` |

## Integration

### Gateway Routes
```java
this.routes = combine(
    // Discovery endpoints (Gateway-specific)
    post("/discovery").to(() -> new DiscoveryRegistrationHandler()),
    delete("/discovery").to(() -> new DiscoveryUnregistrationHandler()),
    // ... other routes
);
```

### GridInstanceRegistry Integration
- **Registration**: Uses `addGridInstance(instanceId, baseUri)`
- **Unregistration**: Uses `removeGridInstance(instanceId)`
- **Instance Lookup**: Searches by URI for unregistration
- **Health Checking**: Automatically starts for new instances

## Usage Examples

### Register Grid Instance
```bash
curl -X POST http://gateway:4444/discovery \
  -H "Content-Type: application/json" \
  -d '{"url": "http://grid1:4444"}'
```

### Unregister Grid Instance (Query Parameter)
```bash
curl -X DELETE "http://gateway:4444/discovery?url=http://grid1:4444"
```

### Unregister Grid Instance (JSON Body)
```bash
curl -X DELETE http://gateway:4444/discovery \
  -H "Content-Type: application/json" \
  -d '{"url": "http://grid1:4444"}'
```

## Testing

### Test Coverage
- **Successful Registration**: Valid URL registration
- **Registration Validation**: Missing URL error handling
- **Successful Unregistration**: Valid URL unregistration
- **Unregistration Validation**: Missing URL error handling
- **Instance Not Found**: Non-existent URL handling

### Test Implementation
- **Location**: `DiscoveryEndpointTest.java`
- **Framework**: JUnit 5 with AssertJ
- **Coverage**: All success and error scenarios

## Security Considerations

### Input Validation
- **URL Format**: Validates URI format before registration
- **JSON Parsing**: Handles malformed JSON gracefully
- **Parameter Extraction**: Safely extracts URL from requests

### Access Control
- **Authentication**: Consider adding authentication for production use
- **Authorization**: Restrict registration/unregistration to authorized clients
- **Rate Limiting**: Implement rate limiting to prevent abuse

## Monitoring and Logging

### Logging Events
- **Registration Success**: Instance ID and URL logged
- **Unregistration Success**: Instance ID and URL logged
- **Errors**: All error scenarios logged with details
- **Request Metrics**: Track discovery request counts

### Metrics
- **Discovery Requests**: Counter for total discovery operations
- **Registration Success**: Counter for successful registrations
- **Unregistration Success**: Counter for successful unregistrations
- **Error Rates**: Track error rates by type

## Best Practices

1. **URL Validation**: Always validate URLs before registration
2. **Error Handling**: Provide clear error messages for debugging
3. **Logging**: Log all discovery operations for audit trails
4. **Health Checks**: Ensure registered instances are healthy
5. **Cleanup**: Implement automatic cleanup for stale instances
6. **Documentation**: Keep API documentation up to date
7. **Testing**: Test all error scenarios thoroughly