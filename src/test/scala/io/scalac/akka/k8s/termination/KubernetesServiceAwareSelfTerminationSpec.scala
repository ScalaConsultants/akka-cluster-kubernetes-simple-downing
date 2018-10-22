package io.scalac.akka.k8s.termination

import java.net.ServerSocket
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.{ Eventually, IntegrationPatience }
import org.scalatest.{ Matchers, WordSpec }

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

    "terminate actor system when API Service is unavailable for too long" in {
      sys.env("KUBERNETES_SERVICE_HOST") shouldBe "localhost"
      sys.env("KUBERNETES_SERVICE_PORT") shouldBe "12345"

      val (counter, switchOff) = runFakeApiService()
      val system               = ActorSystem()

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
          |akka.k8s.self-termination.api-service-probe-interval = 100ms
          |akka.k8s.self-termination.api-service-failures-allowed = 30
        """.stripMargin)
        .withFallback(ConfigFactory.load())

      val system               = ActorSystem("temporarily-unavailable", config)
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

    "throw exception during extension creation if hostname env variable is incorrect" in {
      sys.env.get("NOT_DEFINED_ENV_VAR") shouldBe empty

      val config = ConfigFactory
        .parseString(
          """
          |akka.extensions = ["io.scalac.akka.k8s.termination.KubernetesServiceAwareSelfTermination"]
          |akka.k8s.self-termination.api-service-host-env-name = "NOT_DEFINED_ENV_VAR"
          |""".stripMargin
        )
        .withFallback(ConfigFactory.load())

      val exception = intercept[Exception](ActorSystem("extension-config-test", config))
      exception.getMessage should include(
        "Can't get Kubernetes API Service Hostname from environment variable"
      )
    }

    "throw exception during extension creation if port env variable is incorrect" in {
      sys.env.get("NOT_DEFINED_ENV_VAR") shouldBe empty

      val config = ConfigFactory
        .parseString(
          """
          |akka.extensions = ["io.scalac.akka.k8s.termination.KubernetesServiceAwareSelfTermination"]
          |akka.k8s.self-termination.api-service-port-env-name = "NOT_DEFINED_ENV_VAR"
          |""".stripMargin
        )
        .withFallback(ConfigFactory.load())

      val exception = intercept[Exception](ActorSystem("extension-config-test", config))
      exception.getMessage should include(
        "Can't get Kubernetes API Service Port from environment variable"
      )
    }

    "throw exception during extension creation if port env variable does not parse to int" in {
      sys.env("INVALID_KUBERNETES_SERVICE_PORT") shouldBe "not an integer"

      val config = ConfigFactory
        .parseString(
          """
          |akka.extensions = ["io.scalac.akka.k8s.termination.KubernetesServiceAwareSelfTermination"]
          |akka.k8s.self-termination.api-service-port-env-name = "INVALID_KUBERNETES_SERVICE_PORT"
          |""".stripMargin
        )
        .withFallback(ConfigFactory.load())

      val exception = intercept[Exception](ActorSystem("extension-config-test", config))
      exception.getMessage should include(
        "Can't get Kubernetes API Service Port from environment variable"
      )
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
