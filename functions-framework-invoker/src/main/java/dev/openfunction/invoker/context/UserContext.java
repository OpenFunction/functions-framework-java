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

package dev.openfunction.invoker.context;

import dev.openfunction.functions.*;
import dev.openfunction.invoker.Callback;
import dev.openfunction.invoker.JsonEventFormat;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.v03.CloudEventBuilder;
import io.dapr.client.DaprClient;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URI;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UserContext implements Context {

    private static final Logger logger = Logger.getLogger("dev.openfunction.invoker");

    private static final Set<String> MiddlewaresCloudEventFormatRequired = Set.of(
            "bindings.kafka",
            "bindings.kubemq",
            "bindings.mqtt3",
            "bindings.rabbitmq",
            "bindings.redis",
            "bindings.gcp.pubsub",
            "bindings.azure.eventhubs"
    );

    private final RuntimeContext runtimeContext;
    private final DaprClient daprClient;

    private Out out;

    private BindingEvent bindingEvent;
    private TopicEvent topicEvent;
    private CloudEvent cloudEvent;

    private HttpRequest httpRequest;
    private HttpResponse httpResponse;

    private Object function;

    public UserContext(RuntimeContext runtimeContext, DaprClient daprClient) {
        this.runtimeContext = runtimeContext;
        this.daprClient = daprClient;
    }

    public UserContext withHttp(HttpRequest httpRequest, HttpResponse httpResponse) {
        this.httpRequest = httpRequest;
        this.httpResponse = httpResponse;
        return this;
    }

    public UserContext withBindingEvent(BindingEvent event) {
        this.bindingEvent = event;
        return this;
    }

    public UserContext withTopicEvent(TopicEvent event) {
        this.topicEvent = event;
        return this;
    }

    @Override
    @Deprecated
    public Error send(String outputName, String data) {
        if (data == null) {
            return null;
        }
        Map<String, Component> outputs = runtimeContext.getFunctionContext().getOutputs();
        if (outputs.isEmpty()) {
            return new Error("no output");
        }

        Component output = outputs.get(outputName);
        if (output == null) {
            return new Error("output " + outputName + " not found");
        }

        if (output.isPubsub()) {
            daprClient.publishEvent(output.getComponentName(), output.getTopic(), data);
        } else if (output.isBinding()) {
            // If a middleware supports both binding and pubsub, then the data send to
            // binding must be in CloudEvent format, otherwise pubsub cannot parse the data.
            byte[] payload = data.getBytes();
            if (MiddlewaresCloudEventFormatRequired.contains(output.getComponentType())) {
                payload = packageAsCloudevent(data);
            }

            daprClient.invokeBinding(output.getComponentName(), output.getOperation(), payload).block();
        } else {
            return new Error("unsupported output type " + output.getComponentType());
        }

        return null;
    }

    @Override
    public HttpRequest getHttpRequest() {
        return httpRequest;
    }

    @Override
    public HttpResponse getHttpResponse() {
        return httpResponse;
    }

    @Override
    public BindingEvent getBindingEvent() {
        return bindingEvent;
    }

    @Override
    public TopicEvent getTopicEvent() {
        return topicEvent;
    }

    @Override
    public CloudEvent getCloudEvent() {
        return cloudEvent;
    }

    @Override
    public String getName() {
        return runtimeContext.getFunctionContext().getName();
    }

    @Override
    public Out getOut() {
        return out;
    }

    @Override
    public String getHttpPattern() {
        return null;
    }

    @Override
    public Map<String, Component> getOutputs() {
        return runtimeContext.getFunctionContext().getOutputs();
    }

    @Override
    public Map<String, Component> getStates() {
        return runtimeContext.getFunctionContext().getStates();
    }

    @Override
    public DaprClient getDaprClient() {
        return daprClient;
    }

    @Override
    public byte[] packageAsCloudevent(String payload) {
        CloudEvent event = new CloudEventBuilder()
                .withId(UUID.randomUUID().toString())
                .withType("dapr.invoke")
                .withSource(URI.create("openfunction/invokeBinding"))
                .withData(payload.getBytes())
                .withDataContentType(JsonEventFormat.CONTENT_TYPE)
                .build();
        return new JsonEventFormat().serialize(event);
    }

    @Override
    public Map<String, Component> getInputs() {
        return runtimeContext.getInputs();
    }

    public Class<?> getFunctionClass() {
        return function.getClass();
    }

    private void executeHooks(boolean pre) throws Exception {
        Map<String, Object> hooks;
        if (pre) {
            hooks = runtimeContext.getPreHooks();
        } else {
            hooks = runtimeContext.getPostHooks();
        }
        for (String name : hooks.keySet()) {
            Object obj = hooks.get(name);
            if (Hook.class.isAssignableFrom(obj.getClass())) {
                executeHook(((Hook) obj).init());
            }

            if (Plugin.class.isAssignableFrom(obj.getClass())) {
                executePlugin(((Plugin) obj).init(), pre);
            }
        }
    }

    private void executePlugin(Plugin plugin, boolean pre) throws Exception {
        if (plugin.needToTracing()) {
            runtimeContext.executeWithTracing(plugin, () -> {
                Error error;
                if (pre) {
                    error = plugin.execPreHook(UserContext.this);
                } else {
                    error = plugin.execPostHook(UserContext.this);
                }
                if (error != null) {
                    logger.log(Level.SEVERE, "execute plugin " + plugin.name() + ":" + plugin.version() + " error", error);
                }

                return error;
            });
        } else {
            Error error;
            if (pre) {
                error = plugin.execPreHook(UserContext.this);
            } else {
                error = plugin.execPostHook(UserContext.this);
            }
            if (error != null) {
                logger.log(Level.SEVERE, "execute plugin " + plugin.name() + ":" + plugin.version() + " error", error);
            }
        }
    }

    private void executeHook(Hook hook) throws Exception {
        if (hook.needToTracing()) {
            runtimeContext.executeWithTracing(hook, () -> {
                Error error = hook.execute(UserContext.this);
                if (error != null) {
                    logger.log(Level.SEVERE, "execute hook " + hook.name() + ":" + hook.version() + " error", error);
                }

                return error;
            });
        } else {
            Error error = hook.execute(UserContext.this);
            if (error != null) {
                logger.log(Level.SEVERE, "execute hook " + hook.name() + ":" + hook.version() + " error", error);
            }
        }
    }

    public void executeFunction(HttpFunction function) throws Exception {
        this.function = function;
        executeFunction(() -> {
            function.service(this.httpRequest, this.httpResponse);
            return null;
        });
    }

    public void executeFunction(CloudEventFunction function, CloudEvent event) throws Exception {
        this.function = function;
        this.cloudEvent = event;
        executeFunction(() -> {
            Error err = function.accept(UserContext.this, event);
            if (err == null) {
                httpResponse.setStatusCode(HttpServletResponse.SC_OK);
                httpResponse.getOutputStream().write(out == null || out.getData() == null ? "Success".getBytes() : out.getData().array());
            } else {
                httpResponse.setStatusCode(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                httpResponse.getOutputStream().write(err.getMessage().getBytes());
            }
            return null;
        });
    }

    public void executeFunction(OpenFunction function, String payload) throws Exception {
        this.function = function;
        executeFunction(() -> {
            out = function.accept(UserContext.this, payload);
            if (httpResponse != null) {
                if (out == null || out.getError() == null) {
                    httpResponse.setStatusCode(HttpServletResponse.SC_OK);
                    httpResponse.getOutputStream().write(out == null || out.getData() == null ? "Success".getBytes() : out.getData().array());
                } else {
                    httpResponse.setStatusCode(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    httpResponse.getOutputStream().write(out.getError().getMessage().getBytes());
                }
            }

            return out == null ? null : out.getError();
        });
    }

    private void executeFunction(Callback callBack) throws Exception {
        runtimeContext.executeWithTracing(this,
                () -> {
                    executeHooks(true);
                    runtimeContext.executeWithTracing(null, callBack);
                    executeHooks(false);
                    return null;
                });
    }
}
