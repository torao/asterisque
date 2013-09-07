#!/bin/sh
java -Xmx512M -Dscala.home="~/lib/scala-2.9.2" -jar `dirname $0`/sbt-launch.jar "$@"
