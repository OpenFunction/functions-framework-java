#!/bin/bash

mvn clean compile dependency:copy-dependencies
mvn exec:exec -Dexec.executable="java" -Dexec.args="-javaagent:./skywalking-agent/skywalking-agent.jar -classpath samples-1.0-SNAPSHOT.jar:target/classes/:target/dependency/* dev.openfunction.invoker.Runner"
