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

package dev.openfunction.invoker.tracing;

import dev.openfunction.functions.*;
import dev.openfunction.invoker.Callback;
import dev.openfunction.invoker.context.TracingConfig;
import dev.openfunction.invoker.context.UserContext;
import io.cloudevents.CloudEvent;
import org.apache.skywalking.apm.toolkit.trace.*;

import java.util.*;

public class SkywalkingProvider implements TracingProvider {
    /**
     * <a href="https://github.com/apache/skywalking/blob/master/oap-server/server-starter/src/main/resources/component-libraries.yml#L515">...</a>
     */
//    private static final int componentIDOpenFunction = 5013;

    private final String functionName;
    private Map<String, String> tags;
    private final Map<String, String> baggage;

    public SkywalkingProvider(TracingConfig config, String functionName, String pod, String namespace) {
        this.functionName = functionName;

        tags = config.getTags();
        tags = config.getTags();
        if (tags == null) {
            tags = new HashMap<>();
        }
        if (!Objects.equals(pod, "")) {
            tags.put("instance", pod);
        }
        if (!Objects.equals(namespace, "")) {
            tags.put("namespace", pod);
        }
        baggage = config.getBaggage();
    }

    @Override
    public void executeWithTracing(HttpRequest httpRequest, Callback callback) throws Exception {
        Map<String, String> carrier = new HashMap<>();
        Map<String, List<String>> headers = httpRequest.getHeaders();
        if (headers != null) {
            for (String key : headers.keySet()) {
                if (headers.get(key).size() > 0) {
                    carrier.put(key, headers.get(key).get(0));
                }
            }
        }

        HashMap<String, String> newTags = new HashMap<>(tags);
        newTags.put("Method", httpRequest.getMethod());
        newTags.put("URI", httpRequest.getUri());

        executeWithTracing(carrier, newTags, callback);
    }

    @Override
    public void executeWithTracing(CloudEvent event, Callback callback) throws Exception {
        Map<String, String> carrier = new HashMap<>();
        for (String key : event.getExtensionNames()) {
            Object obj = event.getExtension(key);
            carrier.put(key, obj == null ? "" : obj.toString());
        }
        executeWithTracing(carrier, tags, callback);
    }

    @Override
    public void executeWithTracing(BindingEvent event, Callback callback) throws Exception {
        executeWithTracing(new HashMap<>(), tags, callback);
    }

    @Override
    public void executeWithTracing(TopicEvent event, Callback callback) throws Exception {
        executeWithTracing(new HashMap<>(), tags, callback);
    }

    private void executeWithTracing(Map<String, String> carrier, Map<String, String> tags, Callback callback) throws Exception {
        ContextCarrierRef contextCarrierRef = new ContextCarrierRef();
        if (carrier != null) {
            CarrierItemRef next = contextCarrierRef.items();
            while (next.hasNext()) {
                next = next.next();
                if (carrier.get(next.getHeadKey()) != null) {
                    next.setHeadValue(carrier.get(next.getHeadKey()));
                }
            }
        }

        SpanRef span = Tracer.createEntrySpan(functionName, contextCarrierRef);
        if (tags != null) {
            for (String key : tags.keySet()) {
                span.tag(key, tags.get(key));
            }
        }

        if (baggage != null) {
            for (String key : baggage.keySet()) {
                TraceContext.putCorrelation(key, baggage.get(key));
            }
        }

        Error err = callback.execute();
        if (err != null) {
            ActiveSpan.error(err);
        }
        Tracer.stopSpan();
    }

    @Override
    public void executeWithTracing(Callback callback) throws Exception {
        executeWithTracing("function", null, callback);
    }

    @Override
    public void executeWithTracing(Plugin plugin, Callback callback) throws Exception {
        Map<String, String> tags = new HashMap<>();
        tags.put("kind", "Plugin");
        tags.put("name", plugin.name());
        tags.put("version", plugin.version());
        if (plugin.tagsAddToTracing() != null) {
            tags.putAll(plugin.tagsAddToTracing());
        }

        executeWithTracing(plugin.name(), tags, callback);
    }

    @Override
    public void executeWithTracing(Hook hook, Callback callback) throws Exception {
        Map<String, String> tags = new HashMap<>();
        tags.put("kind", "Hook");
        tags.put("name", hook.name());
        tags.put("version", hook.version());
        if (hook.tagsAddToTracing() != null) {
            tags.putAll(hook.tagsAddToTracing());
        }

        executeWithTracing(hook.name(), tags, callback);
    }

    @Override
    public void executeWithTracing(UserContext ctx, Callback callback) throws Exception {
        Map<String, String> tags = new HashMap<>();
        tags.put("function", ctx.getFunctionClass().getName());

        executeWithTracing(ctx.getFunctionClass().getSimpleName(), tags, callback);
    }

    private void executeWithTracing(String name, Map<String, String> tags, Callback callback) throws Exception {
        SpanRef span = Tracer.createLocalSpan(name);

        if (tags != null) {
            for (String key : tags.keySet()) {
                span.tag(key, tags.get(key));
            }
        }

        Error err = callback.execute();
        if (err != null) {
            ActiveSpan.error(err);
        }

        Tracer.stopSpan();
    }
}
