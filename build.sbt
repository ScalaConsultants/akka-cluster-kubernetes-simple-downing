name := "akka-cluster-kubernetes-simple-downing"
organization := "scalac.io"
version := "1.0.0-SNAPSHOT"

scalaVersion := "2.12.7"

val Version = new {
  val akka = "2.5.17"
  val scalaTest = "3.0.5"
}

lazy val `akka-cluster-kubernetes-simple-downing` = project.in(file("."))
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster" % Version.akka,
      "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % "0.18.0",
      "org.slf4j" % "slf4j-api" % "1.7.25",
      "org.scalatest" %% "scalatest" % Version.scalaTest % "test",
      "com.typesafe.akka" %% "akka-multi-node-testkit" % Version.akka % "test"
    ),
    scalacOptions ++= Seq(
      "-encoding", "utf8",
      "-Xfatal-warnings",
      "-deprecation",
      "-unchecked",
      "-language:higherKinds"
    )

  )
