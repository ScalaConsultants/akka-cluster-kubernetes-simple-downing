package io.scalac.akka.k8s.downing

import java.net.Socket
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, DowningProvider, Member, MemberStatus}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class KubernetesServiceAwareDowningSettings(config: Config) {
  private val c = config.getConfig("akka.k8s.downing")

  val apiServicePortEnvName = c.getString("api-service-port-env-name")
  val apiServiceHostEnvName = c.getString("api-service-host-env-name")
  val apiServiceProbeInterval = getDuration("api-service-probe-interval")
  val apiServiceFailuresAllowed = c.getInt("api-service-failures-allowed")
  val additionalDownRemovalMargin = getDuration("additional-down-removal-margin")

  private def getDuration(key: String) = Duration(c.getDuration(key, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
}

class KubernetesServiceAwareDowningProvider(system: ActorSystem) extends DowningProvider {

  import scala.concurrent.ExecutionContext.Implicits.global

  val settings = new KubernetesServiceAwareDowningSettings(system.settings.config)

  override def downRemovalMargin: FiniteDuration =
    (settings.apiServiceProbeInterval * (settings.apiServiceFailuresAllowed + 1)) + settings.additionalDownRemovalMargin

  override def downingActorProps: Option[Props] = Some(
    Props(new KubernetesServiceAwareDowning(Cluster(system), settings))
  )

}

class KubernetesServiceAwareDowning(
  cluster: Cluster,
  settings: KubernetesServiceAwareDowningSettings)
  (
    implicit ex: ExecutionContext
  ) extends Actor
  with ActorLogging {

  /** Checks if Kubernetes Service is available by trying to open a socket. */
  private def probe(): Future[Boolean] = Future {
    val host = sys.env.get(settings.apiServiceHostEnvName)
      .filter(_.nonEmpty)
      .getOrElse(throw new RuntimeException("Can't get Kubernetes API Service Hostname"))

    val port = sys.env.get(settings.apiServicePortEnvName)
      .flatMap(p => Try(p.toInt).toOption)
      .getOrElse(throw new RuntimeException("Can't get Kubernetes API Service Port"))

    Try(new Socket(host, port)).isSuccess
  }

  override def preStart(): Unit = {
    KubernetesServiceMonitor.start(
      () => probe(),
      settings.apiServiceProbeInterval,
      settings.apiServiceFailuresAllowed
    )(context.system)
    cluster.subscribe(context.self, classOf[UnreachableMember])
  }

  implicit private val dcThanAgeOrdering: Ordering[Member] =
    Ordering.fromLessThan((a, b) => a.dataCenter < b.dataCenter || b.isOlderThan(a))

  def receive: Receive = {
    case UnreachableMember(unreachable) =>
      val downingNode =
        (cluster.state.members -- cluster.state.unreachable)
          .filter(_.status == MemberStatus.Up)
          .min
      if (downingNode == cluster.selfMember) {
        log.info(s"Self is the oldest node. Downing [$unreachable]!")
        cluster.down(unreachable.address)
      } else {
        log.info(s"[$downingNode] should down [$unreachable].")
      }
  }
}

/** Checks for Kubernetes API Service availability using probe.
  * Lost connectivity means that Kubernetes API Service will spawn new node in place of this one,
  * or even whole new cluster if all akka-cluster nodes were left behind network split.
  * Because of that when connectivity is lost it triggers kill switch to not allow situation
  * when two clusters are running on both sides of network split.
  */
object KubernetesServiceMonitor {

  val log = LoggerFactory.getLogger(this.getClass)

  def start(
    probe: () => Future[Boolean],
    probeInterval: FiniteDuration,
    failuresAllowed: Int
  )(implicit system: ActorSystem) = {
    implicit val mat: Materializer = ActorMaterializer()
    implicit val ec: ExecutionContext = system.dispatcher

    Source.tick(Duration.Zero, probeInterval, ())
      .mapAsync(1)(_ => probe())
      .runWith(Sink.foldAsync(0) {
        case (_, true) =>
          log.debug("Kubernetes API Service is available.")
          Future.successful(0)
        case (failures, false) if failures <= failuresAllowed =>
          log.warn(s"Kubernetes API Service failed ${failures + 1} times.")
          Future.successful(failures + 1)
        case (failures, false) =>
          log.warn(s"Kubernetes API Service failed ${failures + 1} times. Shutting down actor system.")
          system.terminate().map(_ => 0)
      })
  }

}
