organization := "io.asterisque"

name := "asterisque-core"

version := "1.0.0"

scalaVersion := "2.12.7"

resolvers ++= Seq(
	"Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",	// MessagePack
	"Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"   // scalaz-stream_0.5a
)

scalacOptions ++= Seq("-encoding", "UTF-8", "-unchecked", "-deprecation", "-feature")

javacOptions ++= Seq("-encoding", "UTF-8", "-Xlint:unchecked")

//scaladocOptions ++= Seq("-encoding", "UTF-8", "-doc-title", "asterisque 1.0")

libraryDependencies ++= Seq(
  "io.netty"    %  "netty-all"     % "4.1.31.Final",
  "org.msgpack" %  "msgpack"       % "0.6.12",
  "org.slf4j"   %  "slf4j-log4j12" % "1.7.+",
  "org.specs2"  %% "specs2"        % "3.8.+" % Test
)

