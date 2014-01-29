name := "asterisk"

version := "0.1"

scalaVersion := "2.10.3"

resolvers += "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"	// MessagePack

// scalacOptions ++= Seq("-encoding", "UTF-8", "-unchecked", "-deprecation")

// javacOptions ++= Seq("-encoding", "UTF-8")

// scaladocOptions ++= Seq("-encoding", "UTF-8", "-doc-title", "Asterisk 0.1")

libraryDependencies ++= Seq(
  "io.netty" % "netty-all" % "4.0.15.Final",
	"org.msgpack" % "msgpack" % "0.6.9",
	"org.slf4j" % "slf4j-log4j12" % "1.7.5",
	"org.specs2" % "specs2_2.10" % "2.3.7" % "test"
)

// Scala 2.9.1, sbt 0.11.3
// addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.0.0")
