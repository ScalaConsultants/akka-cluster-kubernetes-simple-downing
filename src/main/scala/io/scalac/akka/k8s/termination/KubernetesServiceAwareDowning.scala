package io.scalac.akka.k8s.termination

import java.net.Socket
import java.util.concurrent.TimeUnit

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.stream.scaladsl.{ Sink, Source }
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

/** Checks for Kubernetes API Service availability using probe.
  * Lost connectivity means that Kubernetes API Service will spawn new node in place of this one,
  * or even whole new cluster if all akka-cluster nodes were left behind network split.
  * Because of that when connectivity is lost it triggers kill switch to not allow situation
  * when two clusters are running on both sides of network split.
  */
final class KubernetesServiceAwareSelfTerminationImpl(
  settings: KubernetesServiceAwareSelfTerminationSettings,
  system: ExtendedActorSystem
) extends Extension {

  private val log = LoggerFactory.getLogger(classOf[KubernetesServiceAwareSelfTerminationImpl])
  private implicit val mat: Materializer = ActorMaterializer()(system)
  private implicit val ec: ExecutionContext = ExecutionContext.Implicits.global

  /** Checks if Kubernetes Service is available by trying to open a socket. */
  private val probe: () => Future[Boolean] = {
    val hostEnvName = settings.apiServiceHostEnvName
    val portEnvName = settings.apiServicePortEnvName

    log.debug(s"Will read host name from $hostEnvName environment variable.")
    val host = sys.env
      .get(hostEnvName)
      .filter(_.nonEmpty)
      .getOrElse {
        throw new RuntimeException(
          s"Can't get Kubernetes API Service Hostname from environment variable $hostEnvName."
        )
      }

    log.debug(s"Will read port from $portEnvName environment variable.")
    val port = sys.env
      .get(settings.apiServicePortEnvName)
      .flatMap(p => Try(p.toInt).toOption)
      .getOrElse {
        throw new RuntimeException(
          s"Can't get Kubernetes API Service Port from environment variable $portEnvName."
        )
      }
    log.info(s"Will use '$host:$port' for probing Kubernetes API Service.")
    () =>
      Future(Try(new Socket(host, port)).isSuccess)
  }

  /** Completes when probe failed too many times consecutively. */
  private lazy val monitor: Future[Option[Int]] =
    Source
      .tick(Duration.Zero, settings.apiServiceProbeInterval, ())
      .mapAsync(1)(_ => probe())
      .scan(0) {
        case (_, true) =>
          log.debug("Kubernetes API Service is available.")
          0
        case (failures, false) =>
          log.warn(s"Kubernetes API Service failed ${failures + 1} times.")
          failures + 1
      }
      .filter(_ > settings.apiServiceFailuresAllowed)
      .runWith(Sink.headOption)

  private[termination] def start(): Unit = monitor.onComplete {
    case Success(None) =>
      log.info("Stream completed without emitting. Perhaps application shuts down.")
    case Success(Some(_)) =>
      log.warn("Kubernetes API Service is unreachable. Terminating actor system.")
      system.terminate()
    case Failure(ex) =>
      log.error(s"Monitoring failed, this is fatal. Terminating actor system.", ex)
      system.terminate()
  }
}

object KubernetesServiceAwareSelfTermination
    extends ExtensionId[KubernetesServiceAwareSelfTerminationImpl]
    with ExtensionIdProvider {

  override def lookup = KubernetesServiceAwareSelfTermination

  override def createExtension(
    system: ExtendedActorSystem
  ): KubernetesServiceAwareSelfTerminationImpl = {
    val settings = new KubernetesServiceAwareSelfTerminationSettings(system.settings.config)
    val impl     = new KubernetesServiceAwareSelfTerminationImpl(settings, system)
    impl.start()
    impl
  }

  override def get(system: ActorSystem): KubernetesServiceAwareSelfTerminationImpl = super.get(system)
}

class KubernetesServiceAwareSelfTerminationSettings(config: Config) {
  private val c = config.getConfig("akka.k8s.termination")

  val apiServicePortEnvName: String           = c.getString("api-service-port-env-name")
  val apiServiceHostEnvName: String           = c.getString("api-service-host-env-name")
  val apiServiceProbeInterval: FiniteDuration = getDuration("api-service-probe-interval")
  val apiServiceFailuresAllowed: Int          = c.getInt("api-service-failures-allowed")

  private def getDuration(key: String) =
    Duration(c.getDuration(key, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
}
