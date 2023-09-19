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

import dev.openfunction.functions.Component;
import dev.openfunction.functions.OpenFunction;
import dev.openfunction.functions.Context;
import dev.openfunction.functions.Out;
import io.dapr.client.DaprClient;

public class OpenFunctionImpl implements OpenFunction {

    @Override
    public Out accept(Context context, String payload) throws Exception {
        System.out.printf("receive event: %s", payload).println();

        DaprClient daprClient = context.getDaprClient();
        if (daprClient == null) {
            return new Out();
        }

        if (context.getOutputs() != null) {
            for (String key : context.getOutputs().keySet()) {
                Component output = context.getOutputs().get(key);
                if (output.isPubsub()) {
                    daprClient.publishEvent(output.getComponentName(), output.getTopic(), payload, output.getMetadata()).block();
                    System.out.println("send data to " + output.getComponentName());
                } else if (output.isBinding()) {
                    // We recommend using CloudEvents to pass data between Dapr components.
                    daprClient.invokeBinding(output.getComponentName(), output.getOperation(), context.packageAsCloudevent(payload), output.getMetadata()).block();
                    System.out.println("send data to " + output.getComponentName());
                }
            }
        }
        return new Out();
    }
}
