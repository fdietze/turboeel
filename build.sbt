name := "turboeel"

version := "1.0-SNAPSHOT"

scalaVersion := "2.11.2"

libraryDependencies ++= Seq(
  "pircbot" % "pircbot" % "1.5.0",
  "com.typesafe.akka" %% "akka-actor" % "2.3.6"
)

scalacOptions ++= Seq(
  "-encoding", "UTF-8",
  "-unchecked",
  "-deprecation",
  "-feature",
  "-Yinline", "-Yinline-warnings",
  "-language:_"
  //,"-Xdisable-assertions", "-optimize"
  )

