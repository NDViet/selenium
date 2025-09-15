# Background
Selenium Grid supports distributed deployment where it has Router - Distributor - Session Queue - Session Maps - Nodes, which is called a Grid instance
Assume that Grid operates well and healthy when execute ~200 sessions at a time in parallel with 200 Nodes in workload.
We have done significant improvements to Grid to support more sessions in parallel. But still lots of things to do.

# Proposal
To scale Grid workload much more while improving Grid core itself, we intend to change the deployment with below idea
- Deploy multiple Grid instances (each instance is a group of components Router - Distributor - Session Queue - Session Maps - Nodes)
- All Grid instances share the same Router service to handle all incoming requests
- Implement a new component Grid Load Balancer which connect to Router service, it is able to discover all Grid instances are available and running in back-end
- Grid Load Balancer is able to run with multiple replicas to share the high workload of incoming requests
- Grid Load Balancer extends from Router implementation to handle all WebDriver session from client to particular Grid instance
- Grid Load Balancer implement session distribution strategy to distribute incoming requests to available Grid instances
- For request new session, Grid Load Balancer will route request to available Grid instance. If it fails, it will route to another available Grid instance until all Grid instances are failed or session is created successfully, or retry attempts are exceeded (configurable by user)
- Grid LB is able to keep mapping of client session to Grid instance where Node is running to avoid route incorrect instance, which cause session not found unexpected
- Auto-healing session when connect from client to Grid LB is failed, network glitch, etc. It will retry to connect to Grid LB until it is available back.
