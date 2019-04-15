organization := "io.asterisque"

name := "asterisque-core"

version := "1.0.0"

scalaVersion := "2.12.8"

resolvers ++= Seq(
  "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"//,  // MessagePack
//  "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"   // scalaz-stream_0.5a
)

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
  // "-Ywarn-value-discard",
  "-Yrangepos" /* for Specs2 */
)

javacOptions ++= Seq("-encoding", "UTF-8", "-Xlint:unchecked")

libraryDependencies ++= Seq(
  "io.netty"    %  "netty-all"     % "4.1.34.Final",
  "org.msgpack" %  "msgpack"       % "0.6.12",
  "com.google.code.findbugs" % "jsr305" % "3.0.+",

  // from Carillon
  "org.apache.commons" %  "commons-math3"   % "3.+",
  "commons-codec"      %  "commons-codec"   % "1.+",
  "net.i2p.crypto"     %  "eddsa"           % "0.+",
  "com.typesafe"       %  "config"          % "1.+",
  "com.typesafe.play"  %% "play-json"       % "2.+",
  "org.rocksdb"        %  "rocksdbjni"      % "5.+",
  "ch.qos.logback"     %  "logback-classic" % "1.+",
  "org.specs2"         %% "specs2-core"     % "4.4.+" % Test
)

// javaSource := baseDirectory.value / "generated"
// unmanagedSourceDirectories in Compile += baseDirectory.value / "src/main/generated"
