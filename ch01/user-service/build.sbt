import Dependencies._

lazy val root = (project in file(".")).
  dependsOn(common).
  settings(
    inThisBuild(List(
      organization := "com.navneetgupta",
      scalaVersion := "2.12.6",
      version      := "0.1.0"
    )),
    name := "user-service",
    libraryDependencies ++= Seq(
    	"org.scalatest" %% "scalatest" % "3.0.1" % "test",
    	"com.typesafe.akka" %% "akka-actor" % "2.5.12",
    	"com.typesafe.akka" %% "akka-testkit" % "2.5.12" % Test,
    	"com.typesafe.akka" %% "akka-slf4j" % "2.5.12",
    	"ch.qos.logback" % "logback-classic" % "1.2.3",
    	"com.typesafe.slick" %% "slick" % "3.2.3",
		"org.slf4j" % "slf4j-nop" % "1.6.4",
		"com.typesafe.slick" %% "slick-hikaricp" % "3.2.3",
		"ws.unfiltered" %% "unfiltered-filter" % "0.9.1",
		"ws.unfiltered" %% "unfiltered-netty" % "0.9.1",
		"ws.unfiltered" %% "unfiltered-netty-server" % "0.9.1",
		"ws.unfiltered" %% "unfiltered-json4s" % "0.9.1",
		"org.json4s" %% "json4s-ext" % "3.6.0-M3",
		"postgresql" % "postgresql" % "9.1-901-1.jdbc4",
 		"net.databinder.dispatch" %% "dispatch-core" % "0.13.3",
 		"com.navneetgupta" %% "common" % "0.1.0"
    ),
    dependencyOverrides ++= Seq(
	  "io.netty" % "netty-codec-http" % "4.1.9.Final",
	  "io.netty" % "netty-handler" % "4.1.9.Final",
	  "io.netty" % "netty-codec" % "4.1.9.Final",
	  "io.netty" % "netty-transport" % "4.1.9.Final",
	  "io.netty" % "netty-buffer" % "4.1.9.Final",
	  "io.netty" % "netty-common" % "4.1.9.Final"
	)
  )