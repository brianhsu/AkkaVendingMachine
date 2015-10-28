name := "WordCount"

scalaVersion := "2.11.7"

scalacOptions ++= Seq("-deprecation")

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.4.0"

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.4" % "test"

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.4.0" % "test"

