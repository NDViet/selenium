# Routing Rules

## Overview

Routing Rules provide advanced traffic distribution capabilities based on WebDriver capabilities using the **RuleBased** load balancing strategy. Rules allow fine-grained control over which Grid instances handle specific types of sessions based on browser, platform, version, and custom criteria.

The RuleBased strategy complements the Greedy strategy by providing capability-aware routing with weighted distribution across Grid instances.

## Configuration

### YAML Structure

```yaml
routingRules:
  - match:
      platformName: ["Windows", "macOS"]
    distribute:
      - index: 0    # first available Grid instance
        weight: 70
      - index: 2    # third instance
        weight: 30

  - match:
      platformName: ["Linux"]
    distribute:
      - index: 1    # second instance
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

  - match: {}   # default fallback
    distribute:
      - index: 1
        weight: 60
      - index: 2
        weight: 40
```

### Match Criteria

#### Supported Fields
- **platformName**: Operating system (Windows, Linux, macOS, etc.)
- **browserName**: Browser type (chrome, firefox, safari, edge, etc.)
- **browserVersion**: Browser version (latest, specific versions)
- **additionalCapabilities**: Custom capability matching

#### Matching Logic
- **Array Values**: OR logic - matches any value in the array
- **Multiple Fields**: AND logic - all fields must match
- **Empty Match**: `{}` matches all requests (fallback rule)

### Distribution Rules

#### Weight-Based Distribution
```yaml
distribute:
  - index: 0    # Grid instance index
    weight: 70  # 70% of matching traffic
  - index: 1
    weight: 30  # 30% of matching traffic
```

#### Weight Calculation
- **Total Weight**: Sum of all weights in distribution rule
- **Selection Probability**: `weight / totalWeight`
- **Random Selection**: Weighted random selection algorithm

## Implementation

### Core Classes

#### RuleBasedLoadBalancer
- **Location**: `RuleBasedLoadBalancer.java`
- **Purpose**: Rule evaluation and weighted instance selection
- **Method**: `selectGridInstance(availableInstances, requestedCapabilities)`
- **Strategy**: Implements LoadBalancingStrategy interface

#### RoutingRule
- **Location**: `RoutingRule.java`
- **Components**: Match criteria + List<DistributionTarget>
- **Methods**: `matches(capabilities)`, `getDistribute()`

#### DistributionTarget
- **Location**: `DistributionTarget.java`
- **Fields**: index (Grid instance index), weight (distribution weight)
- **Logic**: Weighted random selection for load distribution

### Rule Evaluation Process

1. **Capability Extraction**: Parse WebDriver capabilities from request
2. **Rule Iteration**: Check each rule in order for matches
3. **Match Evaluation**: Apply AND/OR logic for criteria
4. **Instance Selection**: Use weighted random selection
5. **Availability Check**: Ensure selected instance is available
6. **Fallback**: Use default rule if no specific match

### Selection Algorithm

```java
public Optional<GridInstance> selectGridInstance(
    List<GridInstance> availableInstances, 
    Capabilities requestedCapabilities) {
  
  // Find matching routing rule
  Optional<RoutingRule> matchingRule = findMatchingRule(requestedCapabilities);
  
  if (matchingRule.isEmpty()) {
    return Optional.empty();
  }
  
  // Select instance based on weight distribution
  return selectInstanceByWeight(availableInstances, matchingRule.get());
}
```

## Rule Examples

### Platform-Based Routing

```yaml
routingRules:
  # Windows and macOS to instances 0 and 2
  - match:
      platformName: ["Windows", "macOS"]
    distribute:
      - index: 0
        weight: 70
      - index: 2
        weight: 30
  
  # Linux to instance 1
  - match:
      platformName: ["Linux"]
    distribute:
      - index: 1
        weight: 100
```

### Browser-Based Routing

```yaml
routingRules:
  # Safari only on instance 0 (macOS)
  - match:
      browserName: ["safari"]
    distribute:
      - index: 0
        weight: 100
  
  # Chrome and Firefox distributed
  - match:
      browserName: ["chrome", "firefox"]
    distribute:
      - index: 1
        weight: 50
      - index: 2
        weight: 50
```

### Version-Specific Routing

```yaml
routingRules:
  # Latest versions to high-performance instances
  - match:
      browserName: ["chrome", "firefox"]
      browserVersion: ["latest"]
    distribute:
      - index: 1
        weight: 50
      - index: 2
        weight: 50
  
  # Older versions to dedicated instance
  - match:
      browserName: ["chrome"]
      browserVersion: ["90", "91", "92"]
    distribute:
      - index: 3
        weight: 100
```

### Complex Multi-Criteria

```yaml
routingRules:
  # Specific combination
  - match:
      platformName: ["Windows"]
      browserName: ["chrome"]
      browserVersion: ["latest"]
    distribute:
      - index: 0
        weight: 100
  
  # Broader match
  - match:
      platformName: ["Windows"]
      browserName: ["chrome"]
    distribute:
      - index: 1
        weight: 60
      - index: 2
        weight: 40
```

## Strategy Comparison

### Greedy vs RuleBased

| Feature | Greedy Strategy | RuleBased Strategy |
|---------|----------------|--------------------|
| **Selection Logic** | Highest available capacity | Capability matching + weights |
| **Configuration** | Instance capacities only | Routing rules + weights |
| **Capability Awareness** | No | Yes |
| **Load Distribution** | Fill instances sequentially | Weighted distribution |
| **Complexity** | Simple | Advanced |
| **Use Case** | General load balancing | Specialized routing needs |

### When to Use RuleBased
- **Platform-specific routing**: Route iOS tests to macOS instances
- **Browser specialization**: Route Safari tests to macOS instances
- **Version isolation**: Route specific browser versions to dedicated instances
- **Performance optimization**: Route heavy tests to high-performance instances
- **Compliance requirements**: Route tests to specific geographic regions

## Integration with Gateway

### GridInstanceRegistry Integration

The GridInstanceRegistry integrates RuleBased strategy:

```java
// Configure RuleBased strategy
GridInstanceRegistry registry = new GridInstanceRegistry(
    tracer, httpClientFactory, 
    LoadBalancingStrategy.RULE_BASED,
    healthCheckInterval, healthCheckTimeout, maxFailureCount,
    routingRulesConfig);

// Select instance with capabilities
Optional<GridInstance> selected = registry.selectGridInstanceForNewSession(capabilities);

if (selected.isPresent()) {
  // Route to selected instance
  return createSessionOnGridInstance(req, selected.get(), span);
} else {
  // No suitable instance found
  return new HttpResponse().setStatus(500);
}
```

### Index-Based Routing

The RuleBased strategy uses instance indices for routing:

```java
// Instance indices correspond to registration order
// Index 0 = first registered Grid instance
// Index 1 = second registered Grid instance
// Index 2 = third registered Grid instance

// Out-of-bounds indices are handled gracefully
List<DistributionTarget> validTargets = targets.stream()
    .filter(target -> target.getIndex() < availableInstances.size())
    .toList();

if (validTargets.isEmpty()) {
  LOG.warning("No valid distribution targets found for rule");
  return Optional.empty();
}
```

## Error Handling

### Rule Validation
- **Missing Fields**: Rules with missing required fields are skipped
- **Invalid Indices**: Out-of-bounds indices are ignored
- **Zero Weights**: Rules with zero total weight are skipped
- **Empty Rules**: Empty rule lists fall back to default load balancing

### Runtime Errors
- **No Matching Rule**: Falls back to default load balancing
- **No Available Instance**: Returns empty Optional
- **Invalid Capabilities**: Logs warning and continues
- **Configuration Errors**: Logs errors and uses fallback

### Logging
```java
// Rule matching
LOG.info(String.format("Selected Grid instance %d with weight %d", 
    instanceIndex, target.getWeight()));

// Fallback scenarios
LOG.warning("RuleBased strategy found no matching rules, falling back to Greedy");

// Configuration issues
LOG.warning("No valid distribution targets found for rule");
```

## Best Practices

### Rule Design
1. **Specific to General**: Order rules from most specific to most general
2. **Default Rule**: Always include a catch-all default rule
3. **Weight Balance**: Ensure weights reflect desired distribution
4. **Instance Bounds**: Verify instance indices exist

### Testing
1. **Rule Coverage**: Test all rule combinations
2. **Edge Cases**: Test with missing or invalid capabilities
3. **Load Distribution**: Verify actual traffic distribution
4. **Fallback Behavior**: Test fallback scenarios

### Configuration Management
1. **Version Control**: Track routing rule changes
2. **Validation**: Validate rules before deployment
3. **Monitoring**: Monitor rule effectiveness
4. **Documentation**: Document rule purposes and changes

## Monitoring

### Rule Effectiveness
- **Rule Hit Rates**: Track which rules are used most
- **Distribution Accuracy**: Verify actual vs. expected distribution
- **Fallback Frequency**: Monitor fallback usage
- **Instance Utilization**: Check if rules achieve desired balance

### Metrics
- **Rule Matches**: Counter per rule
- **Instance Selection**: Distribution across instances
- **Fallback Usage**: Default load balancing usage
- **Error Rates**: Rule evaluation errors

## Troubleshooting

### Common Issues

#### Rules Not Matching
- **Capability Format**: Check W3C vs. legacy capability format
- **Case Sensitivity**: Verify exact string matches
- **Array Format**: Ensure arrays are properly formatted
- **Field Names**: Confirm correct capability field names

#### Incorrect Distribution
- **Weight Calculation**: Verify weight arithmetic
- **Instance Availability**: Check if target instances are healthy
- **Rule Order**: Ensure rule precedence is correct
- **Random Variation**: Account for statistical variation

#### Configuration Errors
- **YAML Syntax**: Validate YAML format
- **Index Bounds**: Verify instance indices exist
- **Required Fields**: Ensure all required fields are present
- **Data Types**: Check field data types match expectations

### Debug Information
- **Request Capabilities**: Log incoming capability values
- **Rule Evaluation**: Log which rules match/don't match
- **Selection Process**: Log instance selection decisions
- **Fallback Triggers**: Log when fallback is used

## Testing

### Unit Tests
- **Rule Matching**: Test match criteria logic
- **Weight Distribution**: Verify weighted selection
- **Edge Cases**: Test invalid configurations
- **Fallback Logic**: Test fallback scenarios

### Integration Tests
- **End-to-End**: Full request routing with rules
- **Load Distribution**: Statistical distribution verification
- **Configuration Loading**: YAML parsing and validation
- **Error Handling**: Invalid rule handling

### Test Examples
```java
@Test
void testWindowsPlatformRouting() {
  Capabilities caps = new ImmutableCapabilities("platformName", "Windows");
  
  Optional<GridInstance> selected = loadBalancer.selectGridInstance(gridInstances, caps);
  assertTrue(selected.isPresent());
  
  String selectedId = selected.get().getId();
  assertTrue(selectedId.equals("grid-0") || selectedId.equals("grid-2"),
      "Windows should route to grid-0 or grid-2, got: " + selectedId);
}

@Test
void testOutOfBoundsIndex() {
  List<RoutingRule> rules = Arrays.asList(
      new RoutingRule(
          Map.of("platformName", Arrays.asList("Windows")),
          Arrays.asList(new DistributionTarget(5, 100)) // Index 5 doesn't exist
      )
  );
  
  RuleBasedLoadBalancer testBalancer = new RuleBasedLoadBalancer(rules);
  Capabilities caps = new ImmutableCapabilities("platformName", "Windows");
  
  Optional<GridInstance> selected = testBalancer.selectGridInstance(gridInstances, caps);
  assertFalse(selected.isPresent(), "Should return empty when all indices are out of bounds");
}
```