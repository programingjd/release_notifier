#!/usr/bin/env bash

dir=$(dirname $(readlink /proc/$$/fd/255))

if [ -z "${JAVA_HOME}" ]; then JAVA=java; else JAVA="${JAVA_HOME}/bin/java"; fi

for jar in $dir/build/libs/*.jar
do
  ${JAVA} -jar $jar $*
done
