name := "akka-self-terminating-for-kubernetes"
organization := "scalac.io"
version := "1.0.0-SNAPSHOT"

scalaVersion := "2.12.7"

val Version = new {
  val akka      = "2.5.17"
  val logback   = "1.2.3"
  val scalaTest = "3.0.5"
  val slf4j     = "1.7.25"
}

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"     % Version.akka,
  "com.typesafe.akka" %% "akka-stream"    % Version.akka,
  "org.slf4j"         % "slf4j-api"       % Version.slf4j,
  "org.scalatest"     %% "scalatest"      % Version.scalaTest % "test",
  "ch.qos.logback"    % "logback-classic" % Version.logback % "test"
)
scalacOptions ++= Seq(
  "-encoding",
  "utf8",
  "-Xfatal-warnings",
  "-deprecation",
  "-unchecked",
  "-language:higherKinds"
)

testOptions += Tests.Argument("-oF")

fork in Test := true
envVars in Test := Map(
  "KUBERNETES_SERVICE_HOST" -> "localhost",
  "KUBERNETES_SERVICE_PORT" -> "12345",
  "INVALID_KUBERNETES_SERVICE_PORT" -> "not an integer"
)
