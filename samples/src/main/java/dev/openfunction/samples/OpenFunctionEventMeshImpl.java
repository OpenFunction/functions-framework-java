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

package dev.openfunction.samples;

import org.apache.eventmesh.client.grpc.producer.EventMeshGrpcProducer;
import org.apache.eventmesh.client.tcp.common.EventMeshCommon;
import org.apache.eventmesh.common.Constants;
import org.apache.eventmesh.common.utils.JsonUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

import dev.openfunction.functions.Component;
import dev.openfunction.functions.Context;
import dev.openfunction.functions.OpenFunction;
import dev.openfunction.functions.Out;

public class OpenFunctionEventMeshImpl implements OpenFunction {

    @Override
    public Out accept(Context context, String payload) throws Exception {
        System.out.printf("receive event: %s", payload).println();

        EventMeshGrpcProducer eventMeshGrpcProducer = context.getEventMeshProducer();
        if (eventMeshGrpcProducer == null) {
            return new Out();
        }

        if (context.getOutputs() != null) {
            for (String key : context.getOutputs().keySet()) {
                Component output = context.getOutputs().get(key);
                eventMeshGrpcProducer.publish(buildCloudEvent(output, payload));
            }
        }
        return new Out();
    }

    protected static CloudEvent buildCloudEvent(Component output, String content) {
        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSubject(output.getTopic())
            .withSource(URI.create("/"))
            .withDataContentType("application/cloudevents+json")
            .withType(EventMeshCommon.CLOUD_EVENTS_PROTOCOL_NAME)
            .withData(JsonUtils.toJSONString(content).getBytes(StandardCharsets.UTF_8))
            .withExtension(Constants.EVENTMESH_MESSAGE_CONST_TTL, String.valueOf(4 * 1000))
            .build();

    }
}
