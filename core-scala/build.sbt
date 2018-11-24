organization := "io.asterisque"

name := "asterisque-core"

version := "1.0.0"

scalaVersion := "2.12.7"

resolvers ++= Seq(
  "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"//,  // MessagePack
//  "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"   // scalaz-stream_0.5a
)

javaSource := baseDirectory.value / "generated"

scalacOptions ++= Seq(
  "-encoding", "UTF-8",
  "-unchecked",
  "-deprecation",
  "-feature",
  "-Xlint",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-unused",
  "-Ywarn-unused-import",
  "-Ywarn-value-discard",
  "-Yrangepos" /* for Specs2 */
)

javacOptions ++= Seq("-encoding", "UTF-8", "-Xlint:unchecked")

libraryDependencies ++= Seq(
  "io.netty"    %  "netty-all"     % "4.1.31.Final",
  "org.msgpack" %  "msgpack"       % "0.6.12",
  "org.slf4j"   %  "slf4j-log4j12" % "1.7.+",
  "com.google.code.findbugs" % "jsr305" % "3.0.+",
  "org.specs2"  %% "specs2-core"   % "4.3.4" % Test
)

