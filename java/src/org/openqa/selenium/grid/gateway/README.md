I want to do an enhancement where implement the Service Gateway in front multiple Grid instances to handle routing, load balancing, caching, service discovery better. That would handle high throughput, high traffic in sync mode, and provide a more robust and scalable solution. Following below tasks
1. Expose an endpoint for discovery Grid instances by accept POST /discovery for register and DELETE /discovery for unregister. Payload based on needed attributes to manage a Grid instance, required URL, for others might load default config at gateway.
2. It should include Load Balancer capabilities in java/src/org/openqa/selenium/grid/loadbalancer
3. Health check endpoint /status of Grid instances, it 200 if Grid instance is healthy. Health checks should runnable by interval
4. Handle a smart load balancer following procedure
  Support routing mode: Greedy, RuleBased
   * With Greedy:
   - First check Grid instances capability (via GraphQL state), if having slot capacity, then priority route to that instance (if it is not RoutingRules configured, see below)
     If no slot capacity, then route to the instance with strategy configured
     Greedy (route to an instance until it is full pre-configured estimated capacity, default 500 if not configured). For example `routing_config.yml`
         ```yaml
         instances:
           - index: 0
             maxCapabilities: 200
           - index: 1
             maxCapabilities: 500
           - index: 2
             maxCapabilities: 300
         ```
   With RuleBased:
     Set of rules based on Capabilities, Priorities, Weight pre-configured. With index is number instance in system, if index over bound, then rule not applicable. For example same `routing_config.yml`
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
6. It should route the same combination handled in java/src/org/openqa/selenium/grid/router/Router.java, java/src/org/openqa/selenium/grid/TemplateGridServerCommand.java
7. It also should handle WebSocket into Grid java/src/org/openqa/selenium/grid/router/ProxyWebsocketsIntoGrid.java
