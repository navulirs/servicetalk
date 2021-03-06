/*
 * Copyright © 2019 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.netty;

import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.concurrent.internal.ServiceTalkTestTimeout;
import io.servicetalk.data.jackson.JacksonSerializationProvider;
import io.servicetalk.http.api.BlockingHttpClient;
import io.servicetalk.http.api.HttpDeserializer;
import io.servicetalk.http.api.HttpExecutionStrategy;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.HttpSerializationProvider;
import io.servicetalk.http.api.HttpSerializer;
import io.servicetalk.serialization.api.TypeHolder;
import io.servicetalk.transport.api.ServerContext;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.Map;

import static io.servicetalk.concurrent.internal.DeliberateException.DELIBERATE_EXCEPTION;
import static io.servicetalk.http.api.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.servicetalk.http.api.HttpSerializationProviders.jsonSerializer;
import static io.servicetalk.http.netty.HttpTestExecutionStrategy.CACHED;
import static io.servicetalk.http.netty.HttpTestExecutionStrategy.NO_OFFLOAD;
import static io.servicetalk.transport.netty.internal.AddressUtils.localAddress;
import static io.servicetalk.transport.netty.internal.AddressUtils.serverHostAndPort;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class HttpSerializationErrorTest {
    @Rule
    public final Timeout timeout = new ServiceTalkTestTimeout();

    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    private HttpExecutionStrategy serverExecutionStrategy;

    public HttpSerializationErrorTest(HttpTestExecutionStrategy serverStrategy) {
        this.serverExecutionStrategy = serverStrategy.executorSupplier.get();
    }

    @Parameterized.Parameters(name = "serverExecutor={0}")
    public static Collection<HttpTestExecutionStrategy> executors() {
        return asList(NO_OFFLOAD, CACHED);
    }

    @After
    public void teardown() throws Exception {
        Executor executor = serverExecutionStrategy.executor();
        if (executor != null) {
            executor.closeAsync().toFuture().get();
        }
    }

    @Test
    public void serializationMapThrowsPropagatesToClient() throws Exception {
        HttpSerializationProvider jackson = jsonSerializer(new JacksonSerializationProvider());
        TypeHolder<Map<String, Object>> mapType = new TypeHolder<Map<String, Object>>() { };
        HttpSerializer<Map<String, Object>> serializer = jackson.serializerFor(mapType);
        HttpDeserializer<Map<String, Object>> deserializer = jackson.deserializerFor(mapType);
        try (ServerContext srv = HttpServers.forAddress(localAddress(0))
                .executionStrategy(serverExecutionStrategy)
                // We build an aggregated service, but convert to/from the streaming API so that we can easily throw
                // and exception when the entire request is available and follow the control flow that was previously
                // hanging.
                .listenAndAwait((ctx, request, responseFactory) ->
                            responseFactory.ok().toStreamingResponse().payloadBody(
                            request.toStreamingRequest().payloadBody(deserializer).map(result -> {
                                throw DELIBERATE_EXCEPTION;
                            }), serializer).toResponse());
             BlockingHttpClient clt = HttpClients.forSingleAddress(serverHostAndPort(srv)).buildBlocking()) {

            HttpResponse resp = clt.request(clt.post("/foo").payloadBody(emptyMap(), serializer));
            assertEquals(INTERNAL_SERVER_ERROR, resp.status());
        }
    }
}
