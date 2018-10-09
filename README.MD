# akka-cluster-kubernetes-simple-downing

This repository hosts a downing provider for akka-cluster designed for
Kubernetes environment.

### Problem(s)

When running akka-cluster under Kubernetes supervision it is important to consider
*Kubernetes API Service* (*apiservice*) as a node that has to be reachable.
Because when network partition occurs then *apiservice* can not reach liveness probes
of some or all akka-cluster nodes and it will evict PODs that are unreachable and will start new
ones for akka-cluster nodes in available part of network. However such unreachable nodes
are still running on the opposite side of split.
This can possibly lead to situation where two clusters are running on both sides of network split.

Strategy to deal with this situation proposed in this project is to shutdown
self node when *apiservice* becomes unreachable.
This should stop all nodes on split side opposite to *apiservice*.
On *apiservice* split side the oldest node (in the lexicographically least data center) should
down unreachable nodes to recover cluster to healthy state.


#### Scale down problem
It can be observed after scaling down Kubernetes deployment that some nodes
are left in *Leaving* and *unreachable* state. This downing provider downs
such nodes.

## Usage

Add dependency to `build.sbt`:
```sbtshell
libraryDependencies += "io.scalac" %% "akka-cluster-kubernetes-simple-downing" % "1.0.0"
```
and set up downing provider in `application.conf`:
```hocon
akka.cluster.downing-provider-class = "io.scalac.akka.k8s.downing.KubernetesServiceAwareDowningProvider"



```


