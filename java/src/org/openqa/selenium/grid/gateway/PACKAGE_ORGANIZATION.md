# Gateway Package Organization

The gateway package is logically organized into functional groups:

## Core Gateway Components
- `Gateway.java` - Main gateway service with enhanced routing
- `GatewayOptions.java` - Configuration options for gateway
- `GridLoadBalancer.java` - Load balancer implementation
- `GridLoadBalancerFactory.java` - Factory for creating load balancers
- `GridLoadBalancerOptions.java` - Load balancer configuration

## Load Balancing Strategies
- `LoadBalancingStrategy.java` - Interface for load balancing strategies
- `RoundRobinLoadBalancer.java` - Round robin distribution
- `LeastSessionsLoadBalancer.java` - Route to instance with fewest sessions
- `GreedyLoadBalancer.java` - Greedy selection algorithm
- `CompositeLoadBalancer.java` - Combines multiple strategies
- `RetryAwareLoadBalancer.java` - Handles retry logic
- `RuleBasedLoadBalancer.java` - Uses routing rules for selection
- `LoadBalancerFactory.java` - Factory for creating strategies
- `RoutingCache.java` - Cache for concurrent request routing
- `CapabilityMatcher.java` - Matches capabilities to instances

## Grid Instance Management
- `GridInstance.java` - Represents a grid instance
- `GridInstanceRegistry.java` - Manages grid instances
- `SessionGridMapping.java` - Maps sessions to grid instances
- `RegistrationHandler.java` - Handles instance registration
- `InstanceConfig.java` - Configuration for instances

## Routing Rules
- `RoutingRule.java` - Individual routing rule
- `RoutingRulesConfig.java` - Configuration for routing rules
- `MatchCriteria.java` - Criteria for matching requests
- `DistributeRule.java` - Distribution rule for routing
- `DistributionTarget.java` - Target for distribution

## Proxy Components
- `ProxyWebsocketsIntoGateway.java` - WebSocket proxy for gateway

## Sub-packages
- `discovery/` - Service discovery components
- `example/` - Example implementations
- `httpd/` - HTTP server components
- `docs/` - Documentation and configuration examples

## Functional Grouping Summary

### 🎯 **Routing & Load Balancing**
All load balancing strategies and routing logic

### 🏗️ **Instance Management** 
Grid instance lifecycle and session mapping

### 📋 **Configuration**
Options, rules, and configuration management

### 🔌 **Proxy & Communication**
WebSocket and HTTP proxying

### 🔍 **Discovery**
Service discovery and registration

This organization keeps related functionality together while maintaining clear separation of concerns.