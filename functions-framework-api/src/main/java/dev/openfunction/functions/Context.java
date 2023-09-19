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

package dev.openfunction.functions;

import org.apache.eventmesh.client.grpc.producer.EventMeshGrpcProducer;

import io.cloudevents.CloudEvent;
import io.dapr.client.DaprClient;

import java.util.Map;

/**
 * An interface for event function context.
 */
public interface Context {
    /**
     * send provides the ability to allow the user to send data to a specified output target.
     *
     * @param outputName output target name
     * @param data       Data String
     * @return Error
     */
    @Deprecated
    Error send(String outputName, String data);

    /**
     * getHttpRequest returns the Http request.
     *
     * @return HttpRequest
     */
    HttpRequest getHttpRequest();

    /**
     * getHttpResponse returns the Http response.
     *
     * @return HttpResponse
     */
    HttpResponse getHttpResponse();

    /**
     * getBindingEvent returns the binding event.
     *
     * @return BindingEvent GetBindingEvent();
     */
    BindingEvent getBindingEvent();

    /**
     * getTopicEvent returns the topic event.
     *
     * @return TopicEvent
     */
    TopicEvent getTopicEvent();

    /**
     * getCloudEvent returns the cloud Event.
     *
     * @return CloudEvent
     */
    CloudEvent getCloudEvent();

    /**
     * getName returns the function's name.
     *
     * @return Function Name
     */
    String getName();

    /**
     * GetOut returns the returned value of function.
     *
     * @return Out
     */
    Out getOut();

    /**
     * getHttpPattern returns the path of the server listening for http function.
     *
     * @return String
     */
    String getHttpPattern();

    /**
     * getInputs returns the inputs of function.
     *
     * @return Inputs
     */
    Map<String, Component> getInputs();

    /**
     * getOutputs returns the Outputs of function.
     *
     * @return Outputs
     */
    Map<String, Component> getOutputs();

    /**
     * getStates returns the states of function.
     *
     * @return states
     */
    Map<String, Component> getStates();

    /**
     * getDaprClient return a dapr client, so that use user
     * can call the dapr API directly.
     * Be carefully, the dapr client maybe null;
     *
     * @return Dapr client
     */
    DaprClient getDaprClient();

    EventMeshGrpcProducer getEventMeshProducer();

    byte[] packageAsCloudevent(String payload);
}
