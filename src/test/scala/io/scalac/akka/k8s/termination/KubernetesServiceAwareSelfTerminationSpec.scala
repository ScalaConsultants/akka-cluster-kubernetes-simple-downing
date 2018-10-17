package io.scalac.akka.k8s.termination

import java.net.ServerSocket
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Tests are forked with environment variables set to match config values.
  * In the real deployment Kubernetes takes care of setting these variables in container.
  */
class KubernetesServiceAwareSelfTerminationSpec
    extends WordSpec
    with Eventually
    with IntegrationPatience
    with Matchers {

  "KubernetesServiceAwareSelfTermination" should {
    "keep probing as long as API Service is available" in {
      val (counter, switchOff) = runFakeApiService()
      val system      = ActorSystem()

      KubernetesServiceAwareSelfTermination(system)

      eventually {
        counter.intValue() should be > 10
      }

      system.terminate()
      switchOff()
    }

    "terminate actor system when API Service is unavailable for too long" in {
      val (counter, switchOff) = runFakeApiService()
      val system      = ActorSystem()

      KubernetesServiceAwareSelfTermination(system)

      eventually {
        counter.intValue() should be > 10
      }

      switchOff()

      eventually(system.whenTerminated.isCompleted shouldBe true)
    }

    "do not terminate actor system when API Service was temporarily unavailable" in {

      // Plenty of failures allowed for CI server as it has 2.5 second spare.
      val config = ConfigFactory
        .parseString("""
          |akka.k8s.termination.api-service-probe-interval = 100ms
          |akka.k8s.termination.api-service-failures-allowed = 30
        """.stripMargin)
        .withFallback(ConfigFactory.load())

      val system      = ActorSystem("test3", config)
      val (counter, switchOff) = runFakeApiService()

      KubernetesServiceAwareSelfTermination(system)

      eventually {
        counter.intValue() should be > 0
      }

      switchOff()
      // About 5 unsuccessful attempts should occur.
      Thread.sleep(500)

      // Start fake API Service again
      val (counter2, switchOff2) = runFakeApiService()
      eventually {
        counter2.intValue() should be > 0
      }

      switchOff2()
      system.terminate()
    }
  }

  "be started automatically if extensions in config are set" in {
    val config = ConfigFactory
      .parseString(
        """akka.extensions = ["io.scalac.akka.k8s.termination.KubernetesServiceAwareSelfTermination"]"""
      )
      .withFallback(ConfigFactory.load())

    val system = ActorSystem("extension-config-test", config)

    eventually(system.whenTerminated.isCompleted shouldBe true)
  }

  type SwitchOff = () => Unit

  /**
    * @return pair of probe counter and switch off
    */
  private def runFakeApiService(): (AtomicInteger, SwitchOff) = {
    val isApiAvailable = new AtomicBoolean(true)
    val counter        = new AtomicInteger(0)
    val ss             = new ServerSocket(12345)
    Future {
      while (isApiAvailable.get()) {
        ss.accept()
        counter.incrementAndGet()
      }
      ss.close()
    }
    (counter, () => isApiAvailable.set(false))
  }
}
