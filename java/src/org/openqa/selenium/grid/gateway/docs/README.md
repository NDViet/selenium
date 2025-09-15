# Gateway Documentation

## Overview

The Selenium Grid Gateway is a service that provides enhanced routing, load balancing, and service discovery for multiple Grid instances. It acts as a smart proxy that distributes WebDriver sessions across multiple backend Grid deployments.

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   WebDriver     │    │   WebDriver     │    │   WebDriver     │
│   Client 1      │    │   Client 2      │    │   Client N      │
└─────────┬───────┘    └─────────┬───────┘    └─────────┬───────┘
          │                      │                      │
          └──────────────────────┼──────────────────────┘
                                 │
                    ┌─────────────▼─────────────┐
                    │                           │
                    │    Selenium Grid         │
                    │       Gateway            │
                    │                           │
                    │  • Load Balancing        │
                    │  • Service Discovery     │
                    │  • Health Checking       │
                    │  • Routing Rules         │
                    │                           │
                    └─────────────┬─────────────┘
                                  │
          ┌───────────────────────┼───────────────────────┐
          │                       │                       │
┌─────────▼─────────┐   ┌─────────▼─────────┐   ┌─────────▼─────────┐
│                   │   │                   │   │                   │
│   Grid Instance   │   │   Grid Instance   │   │   Grid Instance   │
│        1          │   │        2          │   │        3          │
│                   │   │                   │   │                   │
│ Router            │   │ Router            │   │ Router            │
│ Distributor       │   │ Distributor       │   │ Distributor       │
│ SessionMap        │   │ SessionMap        │   │ SessionMap        │
│ Nodes             │   │ Nodes             │   │ Nodes             │
│                   │   │                   │   │                   │
└───────────────────┘   └───────────────────┘   └───────────────────┘
```

## Core Components

### [Greedy Load Balancer](GreedyLoadBalancer.md)
Smart load balancing strategy that maximizes instance utilization before distributing load.

**Key Features:**
- Capacity-based routing
- YAML configuration support
- Default 500 session capacity
- Graceful error handling

### [Discovery Endpoints](DiscoveryEndpoints.md)
Dynamic Grid instance registration and management.

**Endpoints:**
- `POST /discovery` - Register Grid instance
- `DELETE /discovery` - Unregister Grid instance

### [Health Checking](HealthChecking.md)
Continuous monitoring of Grid instance health and availability.

**Features:**
- Periodic health checks
- Automatic failover
- Session count tracking
- Configurable failure thresholds

### [Routing Rules](RoutingRules.md)
YAML-based routing configuration for advanced traffic distribution.

**Capabilities:**
- Match criteria (browser, platform, version)
- Weight-based distribution
- Rule priority handling
- Fallback mechanisms

## Quick Start

### Basic Configuration

```java
// Create Gateway with Greedy load balancing
Gateway gateway = new Gateway(
    tracer,
    httpClientFactory,
    gridInstanceRegistry,
    routingRulesConfig,  // Optional YAML configuration
    maxRetryAttempts,
    requestTimeout,
    publicUri,
    version
);
```

### YAML Configuration Example

```yaml
# Greedy Strategy - Instance capacity configuration
instances:
  - index: 0
    maxCapabilities: 200
  - index: 1
    maxCapabilities: 500
  - index: 2
    maxCapabilities: 300

# RuleBased Strategy - Routing rules configuration
routingRules:
  - match:
      platformName: ["Windows", "macOS"]
    distribute:
      - index: 0
        weight: 70
      - index: 2
        weight: 30
  
  - match:
      platformName: ["Linux"]
    distribute:
      - index: 1
        weight: 100
  
  - match:
      browserName: ["safari"]
    distribute:
      - index: 0
        weight: 100
  
  - match:
      browserName: ["chrome", "firefox"]
      browserVersion: ["latest"]
    distribute:
      - index: 1
        weight: 50
      - index: 2
        weight: 50
  
  - match: {}  # Default fallback
    distribute:
      - index: 1
        weight: 60
      - index: 2
        weight: 40
```

## API Reference

### Session Management
- `POST /session` - Create new WebDriver session (with routing rules)
- `DELETE /session/{sessionId}` - End WebDriver session
- `GET /session/{sessionId}/*` - Forward session commands

### Discovery
- `POST /discovery` - Register Grid instance
- `DELETE /discovery` - Unregister Grid instance

### Health & Status
- `GET /status` - Gateway overall status
- `GET /status/{instanceId}` - Individual Grid instance status

### GraphQL
- `POST /graphql` - Consolidated queries across all Grid instances

## Load Balancing Strategies

| Strategy | Description | Use Case |
|----------|-------------|----------|
| **GREEDY** | Fill instances to capacity before moving to next | Maximize resource utilization |
| **RULE_BASED** | Capability-aware routing with weighted distribution | Platform-specific routing, browser specialization |
| **ROUND_ROBIN** | Distribute requests evenly across instances | Simple load distribution |
| **LEAST_SESSIONS** | Route to instance with fewest active sessions | Balance session load |
| **LEAST_LOAD** | Route to instance with lowest load factor | Consider health and failures |

## Configuration Options

### Health Checking
- **Health Check Interval**: Time between checks (default: 30s)
- **Health Check Timeout**: HTTP timeout (default: 5s)
- **Max Failure Count**: Failures before unhealthy (default: 3)

### Load Balancing
- **Strategy**: Load balancing algorithm
- **Max Retry Attempts**: Session creation retries
- **Request Timeout**: Overall request timeout

### Instance Capacity
- **Default Capacity**: 500 sessions per instance
- **YAML Override**: Per-instance capacity configuration
- **Fallback Behavior**: Invalid values default to 500

## Monitoring

### Metrics
- **Total Requests**: Gateway request count
- **Successful Requests**: Successful session creations
- **Failed Requests**: Failed session attempts
- **Discovery Operations**: Registration/unregistration count
- **Health Check Success Rate**: Instance health metrics

### Logging
- **Request Routing**: Session routing decisions
- **Health Status**: Instance health changes
- **Discovery Events**: Registration/unregistration
- **Error Conditions**: Failures and fallbacks

## Best Practices

### Configuration
1. **Capacity Planning**: Set realistic instance capacities
2. **Health Check Tuning**: Balance responsiveness vs. resource usage
3. **Routing Rules**: Test rules thoroughly before deployment
4. **Fallback Rules**: Always include default routing rules

### Operations
1. **Monitoring**: Track key metrics and health status
2. **Alerting**: Alert on persistent failures
3. **Testing**: Test failover scenarios regularly
4. **Documentation**: Keep configuration documented

### Security
1. **Authentication**: Secure discovery endpoints
2. **Network Security**: Use HTTPS for Grid communication
3. **Access Control**: Restrict administrative operations
4. **Input Validation**: Validate all configuration inputs

## Troubleshooting

### Common Issues
1. **No Available Instances**: Check health status and configuration
2. **Routing Failures**: Verify routing rules and instance indices
3. **Health Check Failures**: Check network connectivity and timeouts
4. **Configuration Errors**: Validate YAML syntax and values

### Debug Information
- **Logs**: Check Gateway logs for routing decisions
- **Status Endpoints**: Use status endpoints for health information
- **Metrics**: Monitor request success/failure rates
- **Configuration**: Verify YAML configuration loading

## Testing

### Unit Tests
- **Load Balancing**: Algorithm correctness
- **Health Checking**: Status transitions and failure handling
- **Discovery**: Registration/unregistration scenarios
- **Configuration**: YAML parsing and validation

### Integration Tests
- **End-to-End**: Full request routing flow
- **Failover**: Instance failure scenarios
- **Load Distribution**: Traffic distribution verification
- **Configuration Changes**: Dynamic reconfiguration

## Contributing

### Development Setup
1. Clone the Selenium repository
2. Navigate to `java/src/org/openqa/selenium/grid/gateway`
3. Run tests: `bazel test //java/test/org/openqa/selenium/grid/gateway:small-tests`

### Code Style
- Follow existing Selenium code conventions
- Add comprehensive tests for new features
- Update documentation for changes
- Ensure backward compatibility

### Documentation
- Update relevant markdown files for changes
- Include code examples for new features
- Add troubleshooting information
- Keep API documentation current