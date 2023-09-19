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

import dev.openfunction.functions.Component;

import java.util.Map;

class FunctionContext {

    private String name;
    private String version;
    private Map<String, Component> inputs;
    private Map<String, Component> outputs;
    private Map<String, Component> states;
    @Deprecated
    private String runtime;
    @Deprecated
    private String port = "8080";

    @Deprecated
    private String[] prePlugins;
    @Deprecated
    private String[] postPlugins;
    @Deprecated
    private TracingConfig pluginsTracing;

    private String[] preHooks;
    private String[] postHooks;
    private TracingConfig tracing;

    private Triggers triggers;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, Component> getInputs() {
        return inputs;
    }

    public void setInputs(Map<String, Component> inputs) {
        this.inputs = inputs;
    }

    public Map<String, Component> getOutputs() {
        return outputs;
    }

    public void setOutputs(Map<String, Component> outputs) {
        this.outputs = outputs;
    }

    public String getRuntime() {
        return runtime;
    }

    public void setRuntime(String runtime) {
        this.runtime = runtime;
    }

    public String getPort() {
        if (triggers != null && triggers.http != null) {
            return triggers.http.port;
        } else {
            return port;
        }
    }

    @Deprecated
    public void setPort(String port) {
        this.port = port;
    }

    @Deprecated
    public String[] getPrePlugins() {
        return prePlugins;
    }

    @Deprecated
    public void setPrePlugins(String[] prePlugins) {
        this.prePlugins = prePlugins;
    }

    @Deprecated
    public String[] getPostPlugins() {
        return postPlugins;
    }

    @Deprecated
    public void setPostPlugins(String[] postPlugins) {
        this.postPlugins = postPlugins;
    }

    @Deprecated
    public TracingConfig getPluginsTracing() {
        return pluginsTracing;
    }

    @Deprecated
    public void setPluginsTracing(TracingConfig pluginsTracing) {
        this.pluginsTracing = pluginsTracing;
    }

    public Map<String, Component> getStates() {
        return states;
    }

    public void setStates(Map<String, Component> states) {
        this.states = states;
    }

    public String[] getPreHooks() {
        return preHooks;
    }

    public void setPreHooks(String[] preHooks) {
        this.preHooks = preHooks;
    }

    public String[] getPostHooks() {
        return postHooks;
    }

    public void setPostHooks(String[] postHooks) {
        this.postHooks = postHooks;
    }

    public TracingConfig getTracing() {
        return tracing;
    }

    public void setTracing(TracingConfig tracing) {
        this.tracing = tracing;
    }

    public Triggers getTriggers() {
        return triggers;
    }

    public void setTriggers(Triggers triggers) {
        this.triggers = triggers;
    }

    static class Triggers {
        private HttpTrigger http;
        private DaprTrigger[] dapr;

        private EventMeshTrigger eventMesh;

        public HttpTrigger getHttp() {
            return http;
        }

        public void setHttp(HttpTrigger http) {
            this.http = http;
        }

        public DaprTrigger[] getDapr() {
            return dapr;
        }

        public void setDapr(DaprTrigger[] dapr) {
            this.dapr = dapr;
        }

        public EventMeshTrigger getEventMesh() {
            return eventMesh;
        }

        public void setEventMesh(EventMeshTrigger eventMesh) {
            this.eventMesh = eventMesh;
        }
    }

    static class HttpTrigger {
        private String port;


        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }
    }

    static class DaprTrigger {
        private String name;
        private String type;
        private String topic;
        private String inputName;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getInputName() {
            return inputName;
        }

        public void setInputName(String inputName) {
            this.inputName = inputName;
        }
    }

    static class EventMeshTrigger {
        private String name;

        private String type;

        private String topic;

        private String eventMeshConnectorAddr;

        private String eventMeshConnectorPort;

        private String producerGroup;

        private String env;

        private String idc;

        private String sysId;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }


        public String getEventMeshConnectorAddr() {
            return eventMeshConnectorAddr;
        }

        public void setEventMeshConnectorAddr(String eventMeshConnectorAddr) {
            this.eventMeshConnectorAddr = eventMeshConnectorAddr;
        }

        public String getEventMeshConnectorPort() {
            return eventMeshConnectorPort;
        }

        public void setEventMeshConnectorPort(String eventMeshConnectorPort) {
            this.eventMeshConnectorPort = eventMeshConnectorPort;
        }

        public String getProducerGroup() {
            return producerGroup;
        }

        public void setProducerGroup(String producerGroup) {
            this.producerGroup = producerGroup;
        }

        public String getEnv() {
            return env;
        }

        public void setEnv(String env) {
            this.env = env;
        }

        public String getIdc() {
            return idc;
        }

        public void setIdc(String idc) {
            this.idc = idc;
        }

        public String getSysId() {
            return sysId;
        }

        public void setSysId(String sysId) {
            this.sysId = sysId;
        }
    }


}
