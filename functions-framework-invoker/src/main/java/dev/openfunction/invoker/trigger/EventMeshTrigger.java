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

package dev.openfunction.invoker.trigger;

import org.apache.commons.collections.MapUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.protobuf.ProtobufFormat;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import dev.openfunction.functions.CloudEventFunction;
import dev.openfunction.functions.Component;
import dev.openfunction.functions.OpenFunction;
import dev.openfunction.invoker.context.RuntimeContext;
import dev.openfunction.invoker.context.UserContext;

import org.apache.eventmesh.client.grpc.config.EventMeshGrpcClientConfig;
import org.apache.eventmesh.client.grpc.producer.EventMeshGrpcProducer;
import org.apache.eventmesh.common.protocol.grpc.cloudevents.CloudEvent;
import org.apache.eventmesh.connector.openfunction.client.CallbackServiceGrpc;

/**
 * Executes the user's asynchronous function.
 */
public final class EventMeshTrigger implements Trigger {

    private static final Logger logger = Logger.getLogger("dev.openfunction.invoker");

    private static final EventFormat eventFormat = EventFormatProvider.getInstance().resolveFormat(ProtobufFormat.PROTO_CONTENT_TYPE);

    private final RuntimeContext runtimeContext;

    private final ArrayList<Object> functions;

    private final Service service;

    public EventMeshTrigger(RuntimeContext runtimeContext, Class<?>[] functionClasses) {
        this.runtimeContext = runtimeContext;

        functions = new ArrayList<>();
        for (Class<?> c : functionClasses) {
            try {
                if (OpenFunction.class.isAssignableFrom(c)) {
                    Class<? extends OpenFunction> openFunctionClass = c.asSubclass(OpenFunction.class);
                    functions.add(openFunctionClass.getConstructor().newInstance());
                } else {
                    throw new Error("Unsupported function " + c.getName());
                }

            } catch (ReflectiveOperationException e) {
                throw new Error("Could not construct an instance of " + c.getName(), e);
            }
        }

        service = new Service();
    }

    @Override
    public void start() throws Exception {
        if (runtimeContext.getEventMeshTrigger() == null) {
            throw new Error("no eventmesh trigger defined for the function");
        }

        this.service.start(runtimeContext.getPort());
    }

    @Override
    public void close() {

    }

    private class Service extends CallbackServiceGrpc.CallbackServiceImplBase {

        private Server eventMeshServer;

        private EventMeshGrpcProducer eventMeshGrpcProducer;

        public void start(int port) throws Exception {
            eventMeshServer = ServerBuilder
                .forPort(port)
                .addService(Service.this)
                .build()
                .start();

            // this map only have one trigger
            Component eventMeshTrigger = runtimeContext.getEventMeshTrigger();
            Map<String, String> metaDataMap = eventMeshTrigger.getMetadata();
            EventMeshGrpcClientConfig eventMeshGrpcClientConfig = EventMeshGrpcClientConfig.builder()
                .serverAddr(metaDataMap.get("eventMeshConnectorAddr"))
                .serverPort(Integer.parseInt(metaDataMap.get("eventMeshConnectorPort")))
                .producerGroup(metaDataMap.get("producerGroup"))
                .env(metaDataMap.get("env"))
                .idc(metaDataMap.get("idc"))
                .sys(metaDataMap.get("sysId"))
                .build();

            eventMeshGrpcProducer = new EventMeshGrpcProducer(eventMeshGrpcClientConfig);

            // Now we handle ctrl+c (or any other JVM shutdown)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    eventMeshGrpcProducer.close();
                    eventMeshServer.shutdown();
                })
            );

            eventMeshServer.awaitTermination();
        }

        @Override
        public void onTopicEvent(CloudEvent cloudEvent, StreamObserver<CloudEvent> responseObserver) {

            assert eventFormat != null;
            io.cloudevents.CloudEvent event = eventFormat.deserialize(cloudEvent.toByteArray());

            try {
                runtimeContext.executeWithTracing(event, () -> {
                        for (Object function : functions) {
                            runtimeContext.executeWithTracing(event, () -> {
                                    new UserContext(runtimeContext, eventMeshGrpcProducer).
                                        executeFunction((OpenFunction) function,
                                            new String(Objects.requireNonNull(event.getData()).toBytes(), StandardCharsets.UTF_8));
                                    return null;
                                }
                            );
                        }
                        responseObserver.onNext(CloudEvent.getDefaultInstance());
                        responseObserver.onCompleted();
                        return null;
                    }
                );
            } catch (Exception e) {
                logger.log(Level.INFO, "catch exception when execute function " + runtimeContext.getName());
                e.printStackTrace();
                responseObserver.onError(e);
            }
        }
    }
}
