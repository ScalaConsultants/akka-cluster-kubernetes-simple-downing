# akka-kubernetes-self-termination

This repository hosts an extension that prevents split brain problem
for akka-clusters running in Kubernetes.

### The problem

When running akka-cluster under Kubernetes supervision it is important to consider
*Kubernetes API Service* (*apiservice*) as a node that has to be reachable.
Because when network partition occurs then *apiservice* can not reach k8s-nodes hosting
some or all akka-cluster nodes and it will evict PODs that are on unreachable nodes and will start new
pods for akka-cluster nodes in available part of network. However such unreachable nodes
are still running on the opposite side of split.
This can possibly lead to situation where two clusters are running on both sides of network split.

Strategy to deal with this situation proposed in this project is to terminate
self node when *apiservice* becomes unreachable.
This should stop all nodes on split side opposite to *apiservice*.
On *apiservice* split side the oldest node (in the lexicographically least data center) should
down unreachable nodes to recover cluster to healthy state.

### Why no split-brain-resolver
This project does not contain split-brain-resolver (which is akka-cluster downing provider 
and self node termination) because it is intended to be combined with one of downing-providers.
It is left for user to decide which one they like.
This project can be seen as a solution to [this issue](https://github.com/akka/akka-management/issues/156).

## Usage

Add dependency to `build.sbt`:
```sbtshell
libraryDependencies += "io.scalac" %% "akka-kubernetes-self-termination" % "1.0.0"
```
and set up akka extension in `application.conf`:
```hocon
akka.extensions = ["io.scalac.akka.k8s.termination.KubernetesServiceAwareSelfTermination"]
```

Please note that this code uses environment variables that are present in Kubernetes run containers.
For some local testing you need to set up `KUBERNETES_SERVICE_HOST` and `KUBERNETES_SERVICE_PORT`.

### How does it work

`KUBERNETES_SERVICE_HOST` and `KUBERNETES_SERVICE_PORT` values are read from environment
and extension keeps probing TCP port of *apiservice*. If a socket can not be created it counts as failed attempt.
After few failed attempts *apiservice* is considered unreachable. This triggers actor system termination
in order to not leave working node in network partition condition.

Developed by [Scalac](https://scalac.io/?utm_source=scalac_github&utm_campaign=scalac1&utm_medium=web)
