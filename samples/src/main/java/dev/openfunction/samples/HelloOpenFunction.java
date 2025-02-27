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

import dev.openfunction.functions.HttpFunction;
import dev.openfunction.functions.HttpRequest;
import dev.openfunction.functions.HttpResponse;
import dev.openfunction.functions.Routable;

public class HelloOpenFunction extends Routable implements HttpFunction {

    @Override
    public void service(HttpRequest request, HttpResponse response) throws Exception {

        response.getWriter().write("Hello OpenFunction");
    }

    @Override
    public String getPath(){
        return "/openfunction";
    }
}

