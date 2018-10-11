package io.scalac.akka.k8s.downing
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch

import akka.actor.Address
import akka.cluster.Cluster
import akka.cluster.MemberStatus.Up
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec, MultiNodeSpecCallbacks}
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit.ImplicitSender
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.implicitConversions

trait STMultiNodeSpec
    extends MultiNodeSpecCallbacks
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll { self: MultiNodeSpec =>

  override def beforeAll(): Unit = multiNodeSpecBeforeAll()
  override def afterAll(): Unit  = multiNodeSpecAfterAll()

  override implicit def convertToWordSpecStringWrapper(s: String): WordSpecStringWrapper =
    new WordSpecStringWrapper(s"$s (on node '${self.myself.name}', $getClass)")
}

object KubernetesDowningSpecConfig extends MultiNodeConfig {
  // apiservice node does not join the cluster, it serves open port for probe and
  // testConductor state is checked at this node. It leads tests.
  val apiservice = role("apiservice")
  val first      = role("first")
  val second     = role("second")
  val third      = role("third")

  val baseConfig = ConfigFactory.parseString(
    """
      | akka.log-dead-letters = off
      | akka.actor.provider = cluster
      | akka.cluster.auto-join = off
      | akka.cluster.downing-provider-class = ""
    """.stripMargin
  )
  commonConfig(
    ConfigFactory
      .parseString(
        """
      | akka.k8s.downing.api-service-probe-interval = 1s
      | akka.k8s.downing.api-service-failures-allowed = 2
      | akka.k8s.downing.additional-down-removal-margin = 1s
      | akka.cluster.downing-provider-class = "io.scalac.akka.k8s.downing.KubernetesServiceAwareDowningProvider"
    """.stripMargin
      )
      .withFallback(baseConfig)
  )

  nodeConfig(apiservice)(baseConfig)

  testTransport(on = true)
}

class KubernetesServiceAwareDowningSpecMultiJvmApiService extends KubernetesServiceAwareDowningSpec
class KubernetesServiceAwareDowningSpecMultiJvmNode1      extends KubernetesServiceAwareDowningSpec
class KubernetesServiceAwareDowningSpecMultiJvmNode2      extends KubernetesServiceAwareDowningSpec
class KubernetesServiceAwareDowningSpecMultiJvmNode3      extends KubernetesServiceAwareDowningSpec

class KubernetesServiceAwareDowningSpec
    extends MultiNodeSpec(KubernetesDowningSpecConfig)
    with STMultiNodeSpec
    with ImplicitSender {

  import KubernetesDowningSpecConfig._

  val apiserviceAddress = node(apiservice).address
  val firstAddress      = node(first).address
  val secondAddress     = node(second).address
  val thirdAddress      = node(third).address

  def initialParticipants = 3

  var serverSocket: Option[ServerSocket] = None
  val shutdownLatch = new CountDownLatch(1)

  "KubernetesServiceAwareDowning" must {
    "have Kubernetes API Service running in the first place" in {
      // 'akka.cluster.auto-join = off thus it does not participate' in the cluster
      runOn(apiservice) {
        implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
        serverSocket = Some(new ServerSocket(12345))
        def acceptAndClose(): Future[Unit] =
          Future {
            serverSocket.foreach(_.accept().close())
          }.flatMap(_ => acceptAndClose())

        acceptAndClose()
      }
      runOn(first, second, third) {
        system.registerOnTermination(shutdownLatch.countDown())
      }
      enterBarrier("apiservice started")
    }

    "start first node" in {
      runOn(first) {
        Cluster(system) join firstAddress
        verifyNodesAreUp(firstAddress)
      }
      enterBarrier("first started")
    }

    "start second node" in {
      runOn(second) {
        Cluster(system) join firstAddress
        verifyNodesAreUp(firstAddress, secondAddress)
      }
      enterBarrier("second started")
    }

    "start third node" in {
      runOn(third) {
        Cluster(system) join firstAddress
        verifyNodesAreUp(firstAddress, secondAddress, thirdAddress)
      }
      enterBarrier("third started")
    }

    "auto-down a node when apiservice is available" in within(10.seconds) {
      runOn(apiservice) {
        // First won't receive heartbeats from third but Down will reach third
        testConductor.blackhole(first, third, Direction.Both).await
        testConductor.blackhole(second, third, Direction.Both).await
      }
      enterBarrier("network partition")

      runOn(first, second) {
        verifyNodesAreUp(firstAddress, secondAddress)
        verifyNodeIsDown(thirdAddress)
      }
      runOn(third) {
        verifyNodeIsDown(firstAddress)
        verifyNodeIsDown(secondAddress)
      }
      enterBarrier("third downed")
    }

    "down entire cluster when apiservice is unavailable" in within(30.seconds) {
      runOn(apiservice) {
        serverSocket.foreach(_.close())
      }
      runOn(first, second, third) {
        shutdownLatch.await()
      }
      runOn(apiservice) {
        awaitCond(testConductor.getNodes.await.size == 1)
      }
    }
  }

  def verifyNodesAreUp(addresses: Address*): Unit =
    addresses.foreach(
      address =>
        awaitCond(Cluster(system).state.members.exists(m => m.address == address && m.status == Up))
    )

  def verifyNodeIsDown(address: Address): Unit =
    awaitCond(Cluster(system).state.members.forall(_.address != address))

}
