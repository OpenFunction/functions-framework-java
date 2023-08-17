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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfunction.functions.*;
import dev.openfunction.invoker.Callback;
import dev.openfunction.invoker.JsonEventFormat;
import dev.openfunction.invoker.tracing.OpenTelemetryProvider;
import dev.openfunction.invoker.tracing.SkywalkingProvider;
import dev.openfunction.invoker.tracing.TracingProvider;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.provider.EventFormatProvider;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RuntimeContext {

    private static final Logger logger = Logger.getLogger("dev.openfunction.invoker");

    static final String PodNameEnvName = "POD_NAME";
    static final String PodNamespaceEnvName = "POD_NAMESPACE";

    @Deprecated
    public static final String SyncRuntime = "Knative";
    @Deprecated
    public static final String AsyncRuntime = "Async";

    private static final String TracingSkywalking = "skywalking";
    private static final String TracingOpentelemetry = "opentelemetry";

    private final FunctionContext functionContext;

    private TracingProvider tracingProvider;

    private Map<String, Object> preHooks;
    private Map<String, Object> postHooks;

    public RuntimeContext(String context, ClassLoader classLoader) throws Exception {
        functionContext = new ObjectMapper().
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).
                readValue(context, FunctionContext.class);

        preHooks = new HashMap<>();
        postHooks = new HashMap<>();

        loadHooks(classLoader);

        TracingConfig  tracingConfig = getTracingConfig();
        if (tracingConfig != null && tracingConfig.isEnabled() && tracingConfig.getProvider() != null) {
            String provider = tracingConfig.getProvider().getName();
            if (!Objects.equals(provider, TracingSkywalking) && !Objects.equals(provider, TracingOpentelemetry)) {
                throw new IllegalArgumentException("unsupported tracing provider " + provider);
            }

            switch (provider) {
                case TracingSkywalking:
                    tracingProvider = new SkywalkingProvider(tracingConfig,
                            functionContext.getName(),
                            System.getenv(RuntimeContext.PodNameEnvName),
                            System.getenv(RuntimeContext.PodNamespaceEnvName));
                    break;
                case TracingOpentelemetry:
                    tracingProvider = new OpenTelemetryProvider(tracingConfig,
                            functionContext.getName(),
                            System.getenv(RuntimeContext.PodNameEnvName),
                            System.getenv(RuntimeContext.PodNamespaceEnvName));
                    break;
            }
        }

        EventFormatProvider.getInstance().registerFormat(new JsonEventFormat());
    }

    private TracingConfig getTracingConfig() {
        TracingConfig tracingConfig = functionContext.getTracing();
        if (tracingConfig != null) {
            return tracingConfig;
        }

        return functionContext.getPluginsTracing();
    }

    private void loadHooks(ClassLoader classLoader) {
        String[] preHookNames = functionContext.getPreHooks();
        if (ArrayUtils.isEmpty(preHookNames)) {
            preHookNames = functionContext.getPrePlugins();
        }

        String[] postHookNames = functionContext.getPostHooks();
        if (ArrayUtils.isEmpty(postHookNames)) {
            postHookNames = functionContext.getPostPlugins();
        }
        preHooks = loadHooks(classLoader, preHookNames);
        postHooks = loadHooks(classLoader, postHookNames);
    }

    private Map<String, Object> loadHooks(ClassLoader classLoader, String[] hookNames) {
        Map<String, Object> hooks = new HashMap<>();
        if (ArrayUtils.isEmpty(hookNames)) {
            return hooks;
        }

        for (String name : hookNames) {
            try {
                Class<?> hookClass = classLoader.loadClass(name);
                if (Hook.class.isAssignableFrom(hookClass)) {
                    Class<? extends Hook> hookImplClass = hookClass.asSubclass(Hook.class);
                    hooks.put(name, hookImplClass.getConstructor().newInstance());
                }

                if (Plugin.class.isAssignableFrom(hookClass)) {
                    Class<? extends Plugin> pluginImplClass = hookClass.asSubclass(Plugin.class);
                    hooks.put(name, pluginImplClass.getConstructor().newInstance());
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "load hook " + name + " error, " + e.getMessage());
                e.printStackTrace();
            }
        }

        return hooks;
    }

    public int getPort() {
        return Integer.parseInt(functionContext.getPort());
    }

    public String getName() {
        return functionContext.getName();
    }

    public Map<String, Component> getInputs() {
        return functionContext.getInputs();
    }

    public void executeWithTracing(Object obj, Callback callback) throws Exception {
        if (tracingProvider != null) {
            if (obj == null) {
                tracingProvider.executeWithTracing(callback);
            } else if (obj instanceof HttpRequest) {
                tracingProvider.executeWithTracing((HttpRequest) obj, callback);
            } else if (obj instanceof CloudEvent) {
                tracingProvider.executeWithTracing((CloudEvent) obj, callback);
            } else if (obj.getClass().isAssignableFrom(TopicEvent.class)) {
                tracingProvider.executeWithTracing((TopicEvent) obj, callback);
            } else if (obj.getClass().isAssignableFrom(BindingEvent.class)) {
                tracingProvider.executeWithTracing((BindingEvent) obj, callback);
            } else if (obj.getClass().isAssignableFrom(UserContext.class)) {
                tracingProvider.executeWithTracing((UserContext) obj, callback);
            } else if (obj instanceof Plugin) {
                tracingProvider.executeWithTracing((Plugin) obj, callback);
            } else if (obj instanceof Hook) {
                tracingProvider.executeWithTracing((Hook) obj, callback);
            }
        } else {
            Error error = callback.execute();
            if (error != null) {
                logger.log(Level.WARNING, "execute failed, ", error);
            }
        }
    }

    public Map<String, Object> getPreHooks() {
        return preHooks;
    }

    public Map<String, Object> getPostHooks() {
        return postHooks;
    }

    public boolean hasHttpTrigger() {
        if (Objects.equals(functionContext.getRuntime(), SyncRuntime)) {
            return true;
        }

        return functionContext.getTriggers() != null && functionContext.getTriggers().getHttp() != null;
    }

    public boolean hasDaprTrigger() {
        if (Objects.equals(functionContext.getRuntime(), AsyncRuntime)) {
            return true;
        }

        return functionContext.getTriggers() != null && ArrayUtils.isNotEmpty(functionContext.getTriggers().getDapr());
    }

    public Map<String, Component> getDaprTrigger() {
        if (Objects.equals(functionContext.getRuntime(), AsyncRuntime)) {
            return functionContext.getInputs();
        }

        if (functionContext.getTriggers() != null &&
                ArrayUtils.isNotEmpty(functionContext.getTriggers().getDapr())) {
            Map<String, Component> triggers = new HashMap<>();
            for (FunctionContext.DaprTrigger trigger : functionContext.getTriggers().getDapr()) {
                Component component = new Component();
                component.setComponentName(trigger.getName());
                component.setComponentType(trigger.getType());
                component.setTopic(trigger.getTopic());
                triggers.put(component.getComponentName(), component);
            }

            return triggers;
        }

        return null;
    }

    public FunctionContext getFunctionContext() {
        return functionContext;
    }

    public boolean needToCreateDaprClient() {
        return (MapUtils.isNotEmpty(functionContext.getInputs())) ||
                (MapUtils.isNotEmpty(functionContext.getOutputs())) ||
                (MapUtils.isNotEmpty(functionContext.getStates()));
    }
}
