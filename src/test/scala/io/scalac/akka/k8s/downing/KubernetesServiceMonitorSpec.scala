package io.scalac.akka.k8s.downing

import akka.actor.ActorSystem
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class KubernetesServiceMonitorSpec extends WordSpec
  with Eventually
  with IntegrationPatience
  with Matchers {

  "KubernetesServiceMonitor" should {
    "keep using probe as long as API Service is available" in {
      implicit val system = ActorSystem()
      var counter = 0
      val probe = () => {
        counter += 1
        Future.successful(true)
      }
      KubernetesServiceMonitor.start(probe, FiniteDuration(1, "ms"), 3)
      eventually {
        counter should be > 10
      }
      system.terminate()
    }

    "terminate down self and terminate actor system when API Service is unavailable" in {
      implicit val system = ActorSystem()
      var counter = 0
      val probe = () => {
        counter += 1
        Future.successful(counter < 10)
      }

      var isTerminated = false
      system.registerOnTermination({
        isTerminated = true
      })

      KubernetesServiceMonitor.start(probe, FiniteDuration(1, "ms"), 3)
      eventually {
        counter should be >= 12
        isTerminated shouldBe true
      }
    }
  }
}
