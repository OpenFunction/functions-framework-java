/*
Copyright 2022 The OpenFunction Authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package dev.openfunction.invoker;


import dev.openfunction.invoker.context.RuntimeContext;
import dev.openfunction.invoker.trigger.DaprTrigger;
import dev.openfunction.invoker.trigger.EventMeshTrigger;
import dev.openfunction.invoker.trigger.HttpTrigger;
import dev.openfunction.invoker.trigger.Trigger;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public class Runner {
    private static final Logger logger = Logger.getLogger(Runner.class.getName());

    private static final String FunctionContext = "FUNC_CONTEXT";
    private static final String FunctionContextV1beta2 = "FUNC_CONTEXT_V1BETA2";
    private static final String FunctionTarget = "FUNCTION_TARGET";
    private static final String FunctionClasspath = "FUNCTION_CLASSPATH";

    public static void main(String[] args) {

        try {
            if (!System.getenv().containsKey(FunctionTarget)) {
                throw new Error(FunctionTarget + " not set");
            }
            String target = System.getenv(FunctionTarget);

            String functionContext = "";
            if (System.getenv().containsKey(FunctionContext)) {
                functionContext = System.getenv(FunctionContext);
            }

            if (System.getenv().containsKey(FunctionContextV1beta2)) {
                functionContext = System.getenv(FunctionContextV1beta2);
            }

            if (StringUtils.isEmpty(functionContext)) {
                throw new Error("Function context not set");
            }

            String classPath = System.getenv().getOrDefault(FunctionClasspath, System.getProperty("user.dir") + "/*");
            ClassLoader functionClassLoader = new URLClassLoader(classpathToUrls(classPath));
            RuntimeContext runtimeContext = new RuntimeContext(functionContext, functionClassLoader);

            Class<?>[] functionClasses = loadTargets(target, functionClassLoader);
            Set<Trigger> triggers = new HashSet<>();
            if (runtimeContext.hasHttpTrigger()) {
                triggers.add(new HttpTrigger(runtimeContext, functionClasses));
            }

            if (runtimeContext.hasDaprTrigger()) {
                triggers.add(new DaprTrigger(runtimeContext, functionClasses));
            }

            if (runtimeContext.hasEventMeshTrigger()) {
                triggers.add(new EventMeshTrigger(runtimeContext, functionClasses));
            }

            for (Trigger trigger : triggers) {
                trigger.start();
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to run function", e);
            e.printStackTrace();
        }
    }

    private static Class<?>[] loadTargets(String target, ClassLoader functionClassLoader) throws ClassNotFoundException {
        String[] targets = target.split(",");
        Class<?>[] classes = new Class<?>[targets.length];
        for (int i = 0; i < targets.length; i++) {
            classes[i] = functionClassLoader.loadClass(targets[i]);
        }

        return classes;
    }

    static URL[] classpathToUrls(String classpath) {
        String[] components = classpath.split(File.pathSeparator);
        List<URL> urls = new ArrayList<>();
        for (String component : components) {
            if (component.endsWith(File.separator + "*")) {
                urls.addAll(jarsIn(component.substring(0, component.length() - 2)));
            } else {
                Path path = Paths.get(component);
                try {
                    urls.add(path.toUri().toURL());
                } catch (MalformedURLException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return urls.toArray(new URL[0]);
    }

    private static List<URL> jarsIn(String dir) {

        Path path = Paths.get(dir);
        if (!Files.isDirectory(path)) {
            return Collections.emptyList();
        }

        try (Stream<Path> stream = Files.list(path)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .map(
                            p -> {
                                try {
                                    return p.toUri().toURL();
                                } catch (MalformedURLException e) {
                                    throw new UncheckedIOException(e);
                                }
                            })
                    .collect(toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
