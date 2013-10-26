#!/bin/sh
java -XX:+CMSClassUnloadingEnabled -XX:MaxPermSize=256m  -Xmx512M -Dscala.home="~/lib/scala-2.10.3" -jar `dirname $0`/sbt-launch.jar "$@"
