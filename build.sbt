organization := "com.kazzla"

name := "asterisque"

version := "1.0-SNAPSHOT"

scalaVersion := "2.11.2"

resolvers ++= Seq(
	"Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",	// MessagePack
	"Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"   // scalaz-stream_0.5a
)

scalacOptions ++= Seq("-encoding", "UTF-8", "-unchecked", "-deprecation", "-feature")

javacOptions ++= Seq("-encoding", "UTF-8")

//scaladocOptions ++= Seq("-encoding", "UTF-8", "-doc-title", "Asterisk 0.1")

libraryDependencies ++= Seq(
  "io.netty"    % "netty-all"     % "4.0.17.Final",
	"org.msgpack" % "msgpack"       % "0.6.10",
	"org.slf4j"   % "slf4j-log4j12" % "1.7.7",
	"org.specs2" % "specs2_2.11" % "2.4.7-scalaz-7.0.6" % "test"
)

// Scala 2.9.1, sbt 0.11.3
// addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.0.0")
