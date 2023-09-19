package dev.openfunction.invoker.tracing;

import dev.openfunction.functions.*;
import dev.openfunction.invoker.Callback;
import dev.openfunction.invoker.context.TracingConfig;
import dev.openfunction.invoker.context.UserContext;
import io.cloudevents.CloudEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter;
import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporterBuilder;
import io.opentelemetry.exporter.jaeger.thrift.JaegerThriftSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.exporter.zipkin.ZipkinSpanExporter;
import io.opentelemetry.exporter.zipkin.ZipkinSpanExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class OpenTelemetryProvider implements TracingProvider {
    private static final String OTEL_LIBRARY_NAME = "opentelemetry-java";
    private static final String OTEL_LIBRARY_VERSION = "1.23.1";

    private static final String Protocol_HTTP = "http";

    private final String functionName;
    private  Map<String, String> tags;
    private final Map<String, String> baggage;
    private final TextMapGetter<Map<String, String>> getter;

    public OpenTelemetryProvider(TracingConfig config, String functionName, String pod, String namespace) throws Exception {
        this.functionName = functionName;

        OpenTelemetrySdkBuilder openTelemetrySdkBuilder = OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()));
        TracingConfig.Exporter exporter = config.getProvider().getExporter();
        if (exporter != null) {
            SpanExporter spanExporter;
            String exporterName = config.getProvider().getExporter().getName();
            switch (exporterName) {
                case "otlp":
                    spanExporter = createOtlpExporter(exporter);
                    break;
                case "jaeger":
                    spanExporter = createJaegerExporter(exporter);
                    break;
                case "zipkin":
                    ZipkinSpanExporterBuilder builder = ZipkinSpanExporter.builder().setEndpoint(exporter.getEndpoint());
                    if (exporter.getTimeout() != null && !exporter.getTimeout().isZero()) {
                        builder.setReadTimeout(exporter.getTimeout());
                    }
                    if (exporter.getCompression() != null && !Objects.equals(exporter.getCompression(), "")) {
                        builder.setCompression(exporter.getCompression());
                    }
                    spanExporter = builder.build();
                    break;
                default:
                    throw new Exception("opentelemetry export not set, use default");
            }

            Resource resource = Resource.getDefault()
                    .merge(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, functionName)));

            SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                    .setResource(resource)
                    .build();
            openTelemetrySdkBuilder.setTracerProvider(sdkTracerProvider);
        }

        openTelemetrySdkBuilder.buildAndRegisterGlobal();

        getter = new TextMapGetter<>() {
            @Override
            public Iterable<String> keys(@NotNull Map<String, String> carrier) {
                return carrier.keySet();
            }

            @Override
            public String get(Map<String, String> carrier, @NotNull String key) {
                return carrier.get(key);
            }
        };

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

    private static SpanExporter createOtlpExporter(TracingConfig.Exporter exporter) {
        String protocol = exporter.getProtocol();
        if (protocol != null && Objects.equals(protocol, Protocol_HTTP)) {
            OtlpHttpSpanExporterBuilder builder = OtlpHttpSpanExporter.builder().setEndpoint(exporter.getEndpoint());
            if (exporter.getTimeout() != null && !exporter.getTimeout().isZero()) {
                builder.setTimeout(exporter.getTimeout());
            }
            if (exporter.getCompression() != null && !Objects.equals(exporter.getCompression(), "")) {
                builder.setCompression(exporter.getCompression());
            }
            if (exporter.getHeaders() != null) {
                for (String key : exporter.getHeaders().keySet()) {
                    builder.addHeader(key, exporter.getHeaders().get(key));
                }
            }
            return JaegerThriftSpanExporter.builder().setEndpoint(exporter.getEndpoint()).build();
        } else {
            OtlpGrpcSpanExporterBuilder builder = OtlpGrpcSpanExporter.builder().setEndpoint(exporter.getEndpoint());
            if (exporter.getTimeout() != null && !exporter.getTimeout().isZero()) {
                builder.setTimeout(exporter.getTimeout());
            }
            if (exporter.getCompression() != null && !Objects.equals(exporter.getCompression(), "")) {
                builder.setCompression(exporter.getCompression());
            }
            if (exporter.getHeaders() != null) {
                for (String key : exporter.getHeaders().keySet()) {
                    builder.addHeader(key, exporter.getHeaders().get(key));
                }
            }
            return builder.build();
        }
    }

    private static SpanExporter createJaegerExporter(TracingConfig.Exporter exporter) {
        String protocol = exporter.getProtocol();
        if (protocol != null && Objects.equals(protocol, Protocol_HTTP)) {
            return JaegerThriftSpanExporter.builder().setEndpoint(exporter.getEndpoint()).build();
        } else {
            JaegerGrpcSpanExporterBuilder builder = JaegerGrpcSpanExporter.builder().setEndpoint(exporter.getEndpoint());
            if (exporter.getTimeout() != null && !exporter.getTimeout().isZero()) {
                builder.setTimeout(exporter.getTimeout());
            }
            if (exporter.getCompression() != null && !Objects.equals(exporter.getCompression(), "")) {
                builder.setCompression(exporter.getCompression());
            }

            return builder.build();
        }
    }

    @Override
    public void executeWithTracing(HttpRequest httpRequest, Callback callback) throws Exception {
        Map<String, String> carrier = new HashMap<>();
        for (String key : httpRequest.getHeaders().keySet()) {
            carrier.put(key, httpRequest.getHeaders().get(key).get(0));
        }

        executeWithTracing(carrier, callback);
    }

    @Override
    public void executeWithTracing(CloudEvent event, Callback callback) throws Exception {
        Map<String, String> carrier = new HashMap<>();
        for (String key : event.getExtensionNames()) {
            Object obj = event.getExtension(key);
            carrier.put(key, obj == null ? "" : obj.toString());
        }

        executeWithTracing(carrier, callback);
    }

    @Override
    public void executeWithTracing(TopicEvent event, Callback callback) throws Exception {
        executeWithTracing(new HashMap<>(), callback);
    }

    @Override
    public void executeWithTracing(BindingEvent event, Callback callback) throws Exception {
        executeWithTracing(new HashMap<>(), callback);
    }

    @Override
    public void executeWithTracing(Callback callback) throws Exception {
        executeWithTracing("function", SpanKind.INTERNAL, null, callback);
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

        executeWithTracing(plugin.name(), SpanKind.INTERNAL, tags, callback);
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

        executeWithTracing(hook.name(), SpanKind.INTERNAL, tags, callback);
    }

    @Override
    public void executeWithTracing(UserContext ctx, Callback callback) throws Exception {
        SpanKind kind = SpanKind.SERVER;
        Map<String, String> tags = new HashMap<>();
        tags.put("function", ctx.getFunctionClass().getName());

        executeWithTracing(ctx.getFunctionClass().getSimpleName(), kind, tags, callback);
    }

    private void executeWithTracing(Map<String, String> carrier, Callback callback) throws Exception {
        TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();
        Context parentContext = propagator.extract(Context.root(), carrier, getter);
        Tracer tracer = GlobalOpenTelemetry.getTracer(OTEL_LIBRARY_NAME, OTEL_LIBRARY_VERSION);
        Span span = tracer.spanBuilder(functionName)
                .setParent(parentContext)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        setGlobalAttribute(span);
        try (Scope ignored = span.makeCurrent()) {
            endSpan(span, callback.execute());
        }
    }

    private void executeWithTracing(String name, SpanKind kind, Map<String, String> tags, Callback callback) throws
            Exception {
        Tracer tracer = GlobalOpenTelemetry.getTracer(OTEL_LIBRARY_NAME, OTEL_LIBRARY_VERSION);
        Span span = tracer.spanBuilder(name)
                .setSpanKind(kind)
                .startSpan();
        span.setAttribute(SemanticAttributes.FAAS_INVOKED_NAME, this.functionName);
        span.setAttribute(SemanticAttributes.FAAS_INVOKED_PROVIDER, "OpenFunction");

        if (tags != null) {
            for (String key : tags.keySet()) {
                span.setAttribute(key, tags.get(key));
            }
        }

        setGlobalAttribute(span);

        try (Scope ignored = span.makeCurrent()) {
            endSpan(span, callback.execute());
        }
    }

    private void endSpan(Span span, Error error) {
        if (error != null) {
            span.setStatus(StatusCode.ERROR, error.getMessage());
        }

        span.end();
    }

    private void setGlobalAttribute(Span span) {
        span.setAttribute(SemanticAttributes.FAAS_INVOKED_NAME, this.functionName);
        span.setAttribute(SemanticAttributes.FAAS_INVOKED_PROVIDER, "OpenFunction");

        if (tags != null) {
            for (String key : tags.keySet()) {
                span.setAttribute(AttributeKey.stringKey(key), tags.get(key));
            }
        }
    }
}
