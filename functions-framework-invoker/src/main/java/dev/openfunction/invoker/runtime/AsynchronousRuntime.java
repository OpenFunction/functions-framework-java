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

import com.google.protobuf.TextFormat;
import dev.openfunction.functions.*;
import dev.openfunction.invoker.context.RuntimeContext;
import dev.openfunction.invoker.context.UserContext;
import io.dapr.client.DaprClient;
import io.dapr.client.DaprClientBuilder;
import io.dapr.v1.AppCallbackGrpc;
import io.dapr.v1.DaprAppCallbackProtos;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes the user's asynchronous function.
 */
public final class AsynchronousRuntime implements Runtime {

    private static final Logger logger = Logger.getLogger("dev.openfunction.invoker");

    private RuntimeContext runtimeContext;

    private final OpenFunction function;

    private final Service service;

    private AsynchronousRuntime(OpenFunction function) {
        this.function = function;
        service = new Service();
    }

    public static AsynchronousRuntime forClass(Class<?> functionClass) {
        if (!OpenFunction.class.isAssignableFrom(functionClass)) {
            throw new RuntimeException(
                    "Class "
                            + functionClass.getName()
                            + " does not implement "
                            + OpenFunction.class.getName());
        }

        try {
            Class<? extends OpenFunction> openFunctionClass = functionClass.asSubclass(OpenFunction.class);
            return new AsynchronousRuntime(openFunctionClass.getConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                    "Could not construct an instance of " + functionClass.getName(), e);
        }
    }

    @Override
    public void start(RuntimeContext ctx) throws Exception {

        AsynchronousRuntime.this.runtimeContext = ctx;

        Map<String, Component> inputs = ctx.getInputs();

        if (inputs == null || inputs.isEmpty()) {
            throw new Error("no inputs defined for the function");
        }

        this.service.start(ctx.getPort());
    }

    @Override
    public void close() {

    }

    private class Service extends AppCallbackGrpc.AppCallbackImplBase {

        private Server daprServer;
        private DaprClient daprClient;

        public void start(int port) throws Exception {

            daprServer = ServerBuilder
                    .forPort(port)
                    .addService(Service.this)
                    .build()
                    .start();

            daprClient = new DaprClientBuilder().build();
            daprClient.waitForSidecar(WaitDaprSidecarTimeout);

            // Now we handle ctrl+c (or any other JVM shutdown)
            java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        daprClient.shutdown();
                        daprServer.shutdown();
                    })
            );

            daprServer.awaitTermination();
        }

        @Override
        public void listInputBindings(com.google.protobuf.Empty request,
                                      io.grpc.stub.StreamObserver<io.dapr.v1.DaprAppCallbackProtos.ListInputBindingsResponse> responseObserver) {

            List<String> inputs = new ArrayList<>();
            for (String key : runtimeContext.getInputs().keySet()) {
                Component input = runtimeContext.getInputs().get(key);
                if (input.getComponentType().startsWith(UserContext.OpenFuncBinding)) {
                    inputs.add(runtimeContext.getInputs().get(key).getComponentName());
                }
            }

            responseObserver.onNext(DaprAppCallbackProtos.ListInputBindingsResponse.newBuilder().addAllBindings(inputs).build());
            responseObserver.onCompleted();
        }

        @Override
        public void onBindingEvent(DaprAppCallbackProtos.BindingEventRequest request,
                                   StreamObserver<DaprAppCallbackProtos.BindingEventResponse> responseObserver) {
            try {
                BindingEvent event = new BindingEvent(request.getName(), request.getMetadataMap(), request.getData().asReadOnlyByteBuffer());
                UserContext userContext = new UserContext(runtimeContext, daprClient, event);

                userContext.executePrePlugins();
                Out out = function.accept(userContext, TextFormat.escapeBytes(request.getData()));
                userContext.setOut(out);
                userContext.executePostPlugins();

                responseObserver.onNext(DaprAppCallbackProtos.BindingEventResponse.getDefaultInstance());
            } catch (Exception e) {
                logger.log(Level.INFO, "catch exception when execute function " + runtimeContext.getName());
                e.printStackTrace();
                responseObserver.onError(e);
            } finally {
                responseObserver.onCompleted();
            }
        }

        @Override
        public void listTopicSubscriptions(com.google.protobuf.Empty request,
                                           io.grpc.stub.StreamObserver<io.dapr.v1.DaprAppCallbackProtos.ListTopicSubscriptionsResponse> responseObserver) {
            List<DaprAppCallbackProtos.TopicSubscription> subscriptions = new ArrayList<>();
            for (String key : runtimeContext.getInputs().keySet()) {
                Component input = runtimeContext.getInputs().get(key);
                if (input.getComponentType().startsWith(UserContext.OpenFuncTopic)) {
                    subscriptions.add(DaprAppCallbackProtos.TopicSubscription.newBuilder().setTopic(input.getUri()).setPubsubName(input.getComponentName()).build());
                }
            }
            responseObserver.onNext(DaprAppCallbackProtos.ListTopicSubscriptionsResponse.newBuilder().addAllSubscriptions(subscriptions).build());
            responseObserver.onCompleted();
        }

        @Override
        public void onTopicEvent(io.dapr.v1.DaprAppCallbackProtos.TopicEventRequest request,
                                 io.grpc.stub.StreamObserver<io.dapr.v1.DaprAppCallbackProtos.TopicEventResponse> responseObserver) {
            try {
                TopicEvent event = new TopicEvent(request.getPubsubName(),
                        request.getId(),
                        request.getTopic(),
                        request.getSpecVersion(),
                        request.getSource(),
                        request.getType(),
                        request.getDataContentType(),
                        request.getData().asReadOnlyByteBuffer());
                UserContext userContext = new UserContext(runtimeContext, daprClient, event);
                userContext.executePrePlugins();
                Out out = function.accept(userContext, TextFormat.escapeBytes(request.getData()));
                userContext.setOut(out);
                userContext.executePostPlugins();

                responseObserver.onNext(DaprAppCallbackProtos.TopicEventResponse.getDefaultInstance());
            } catch (Exception e) {
                logger.log(Level.INFO, "catch exception when execute function " + runtimeContext.getName());
                e.printStackTrace();
                responseObserver.onError(e);
            } finally {
                responseObserver.onCompleted();
            }
        }
    }
}
