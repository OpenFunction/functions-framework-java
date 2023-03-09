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

package dev.openfunction.invoker.runtime;

import dev.openfunction.functions.*;
import dev.openfunction.invoker.context.RuntimeContext;
import dev.openfunction.invoker.context.UserContext;
import dev.openfunction.invoker.http.HttpRequestImpl;
import dev.openfunction.invoker.http.HttpResponseImpl;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.message.MessageReader;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.http.HttpMessageFactory;
import io.dapr.client.DaprClient;
import io.dapr.client.DaprClientBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes the user's synchronize method.
 */
public class SynchronizeRuntime extends HttpServlet implements Runtime {
    private static final Logger logger = Logger.getLogger("dev.openfunction..invoker");

    private final Class<?>[] functionClasses;

    private final RuntimeContext runtimeContext;

    private DaprClient daprClient;

    public SynchronizeRuntime(RuntimeContext runtimeContext, Class<?>[] functionClasses) {
        this.runtimeContext = runtimeContext;
        this.functionClasses = functionClasses;
        EventFormatProvider.getInstance().registerFormat(new JsonEventFormat());
    }

    @Override
    public void start() throws Exception {
        // create dapr client when dapr sidecar enabled.
        if ((runtimeContext.getInputs() != null && !runtimeContext.getInputs().isEmpty()) ||
                (runtimeContext.getOutputs() != null && !runtimeContext.getOutputs().isEmpty())) {
            daprClient = new DaprClientBuilder().build();
            daprClient.waitForSidecar(Runtime.WaitDaprSidecarTimeout);
        }

        ServletContextHandler handler = new ServletContextHandler();
        handler.setContextPath("/");
        for (Class<?> c : functionClasses) {
            Object function;
            if (CloudEventFunction.class.isAssignableFrom(c)) {
                Class<? extends CloudEventFunction> cloudEventFunctionClass = c.asSubclass(CloudEventFunction.class);
                function = cloudEventFunctionClass.getConstructor().newInstance();
            } else if (HttpFunction.class.isAssignableFrom(c)) {
                Class<? extends HttpFunction> httpFunctionClass = c.asSubclass(HttpFunction.class);
                function = httpFunctionClass.getConstructor().newInstance();
            } else if (OpenFunction.class.isAssignableFrom(c)) {
                Class<? extends OpenFunction> openFunctionClass = c.asSubclass(OpenFunction.class);
                function = openFunctionClass.getConstructor().newInstance();
            } else {
                throw new Error("Unsupported function " + c.getName());
            }

            String path = "/*";
            if (Routable.class.isAssignableFrom(c)) {
                path = ((Routable) function).getPath();
            }
            handler.addServlet(new ServletHolder(new OpenFunctionServlet(function)), path);
        }

        Server server = new Server(runtimeContext.getPort());
        server.setHandler(handler);
        server.start();
        server.join();
    }

    @Override
    public void close() {
    }

    class OpenFunctionServlet extends HttpServlet {
        private final Object function;

        public OpenFunctionServlet(Object function) {
            this.function = function;
        }

        /**
         * Executes the user's method, can handle all HTTP type methods.
         */
        @Override
        public void service(HttpServletRequest req, HttpServletResponse res) {
            HttpRequestImpl reqImpl = new HttpRequestImpl(req);
            HttpResponseImpl respImpl = new HttpResponseImpl(res);
            try {
                if (Routable.class.isAssignableFrom(function.getClass())) {
                    List<String> methods = Arrays.asList((((Routable) function).getMethods()));
                    if (methods.stream().noneMatch(req.getMethod()::equalsIgnoreCase)) {
                        respImpl.setStatusCode(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                        return;
                    }
                }

                UserContext userContext = new UserContext(runtimeContext, daprClient, reqImpl, respImpl);
                if (HttpFunction.class.isAssignableFrom(function.getClass())) {
                    userContext.executePrePlugins();
                    ((HttpFunction) function).service(reqImpl, respImpl);

                    if (userContext.getOut() == null) {
                        userContext.setOut(new Out().setCode(respImpl.getStatusCode()));
                    }

                    userContext.executePostPlugins();
                } else if (CloudEventFunction.class.isAssignableFrom(function.getClass())) {
                    MessageReader messageReader = HttpMessageFactory.createReaderFromMultimap(reqImpl.getHeaders(), reqImpl.getInputStream().readAllBytes());
                    CloudEvent event = messageReader.toEvent();
                    userContext.setCloudEvent(event);

                    userContext.executePrePlugins();
                    Error err = ((CloudEventFunction) function).accept(userContext, event);

                    if (userContext.getOut() == null) {
                        userContext.setOut(new Out().setError(err));
                    }

                    userContext.executePostPlugins();
                    if (userContext.getOut() == null) {
                        userContext.setOut(new Out().setError(err));
                    }
                    if (userContext.getOut().getData() == null) {
                        userContext.getOut().setData(ByteBuffer.wrap(("Success".getBytes())));
                    }

                    if (err == null) {
                        respImpl.setStatusCode(HttpServletResponse.SC_OK);
                    } else {
                        respImpl.setStatusCode(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    }

                    respImpl.getOutputStream().write(userContext.getOut().getData().array());
                } else if (OpenFunction.class.isAssignableFrom(function.getClass())) {
                    userContext.executePrePlugins();
                    Out out = ((OpenFunction) function).accept(userContext, new String(reqImpl.getInputStream().readAllBytes()));
                    userContext.setOut(out);
                    userContext.executePostPlugins();

                    if (userContext.getOut() == null) {
                        userContext.setOut(out);
                    }

                    if (userContext.getOut().getData() == null) {
                        userContext.getOut().setData(ByteBuffer.wrap(("Success".getBytes())));
                    }

                    if (out.getError() == null) {
                        respImpl.setStatusCode(HttpServletResponse.SC_OK);
                    } else {
                        respImpl.setStatusCode(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    }

                    respImpl.getOutputStream().write(userContext.getOut().getData().array());
                }
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Failed to execute function", t);
                res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } finally {
                try {
                    // We can't use HttpServletResponse.flushBuffer() because we wrap the PrintWriter
                    // returned by HttpServletResponse in our own BufferedWriter to match our API.
                    // So we have to flush whichever of getWriter() or getOutputStream() works.
                    try {
                        respImpl.getOutputStream().flush();
                    } catch (IllegalStateException e) {
                        respImpl.getWriter().flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
