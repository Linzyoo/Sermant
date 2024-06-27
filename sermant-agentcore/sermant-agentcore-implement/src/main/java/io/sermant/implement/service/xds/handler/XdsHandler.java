/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Based on dubbo-xds/src/main/java/org/apache/dubbo/registry/xds/util/protocol/AbstractProtocol.java
 * from the Apache dubbo project.
 */

package io.sermant.implement.service.xds.handler;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.stub.StreamObserver;
import io.sermant.core.common.LoggerFactory;
import io.sermant.core.utils.FileUtils;
import io.sermant.core.utils.NetworkUtils;
import io.sermant.core.utils.StringUtils;
import io.sermant.implement.service.xds.client.XdsClient;
import io.sermant.implement.service.xds.env.XdsConstant;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * abstract xds protocol handler
 *
 * @author daizhenyu
 * @since 2024-05-13
 **/
public abstract class XdsHandler implements XdsServiceAction {
    protected static final Logger LOGGER = LoggerFactory.getLogger();

    private static final int DELAY_TIME = 3000;

    private static final int THREAD_CORE_SIZE = 1;

    private static final int THREAD_MAX_SIZE = 3;

    private static final long THREAD_KEEP_ALIVE_TIME = 60L;

    private static final int THREAD_QUEUE_CAPACITY = 20;

    private static volatile ThreadPoolExecutor executor;

    protected final XdsClient client;

    protected String resourceType;

    protected Node node;

    /**
     * construction method
     *
     * @param client xds client
     */
    public XdsHandler(XdsClient client) {
        this.client = client;
        createNode();
    }

    /**
     * build DiscoveryRequest
     *
     * @param type resource type
     * @param version resource version
     * @param nonce response nonce
     * @param resourceName resource name
     * @return DiscoveryRequest
     */
    protected DiscoveryRequest buildDiscoveryRequest(String type, String version, String nonce,
            Set<String> resourceName) {
        DiscoveryRequest.Builder builder = DiscoveryRequest.newBuilder()
                .setNode(node)
                .setTypeUrl(type);
        if (version != null) {
            builder.setVersionInfo(version);
        }
        if (nonce != null) {
            builder.setResponseNonce(nonce);
        }
        builder.addAllResourceNames(resourceName);
        return builder.build();
    }

    /**
     * built DiscoveryRequest for ack
     *
     * @param response xds response
     * @param resourceName resource name
     * @return DiscoveryRequest
     */
    protected DiscoveryRequest builtAckDiscoveryRequest(DiscoveryResponse response, Set<String> resourceName) {
        return buildDiscoveryRequest(response.getTypeUrl(), response.getVersionInfo(), response.getNonce(),
                resourceName);
    }

    private void createNode() {
        String fileContent = FileUtils.readFileToString(XdsConstant.K8S_POD_NAMESPACE_PATH);
        String namespace = StringUtils.isEmpty(fileContent) ? XdsConstant.K8S_DEFAULT_NAMESPACE : fileContent;
        StringBuilder nodeIdBuilder = new StringBuilder();

        // nodeId:sidecar~{pod_ip}~{pod_name}.{namespace}~{namespace}.svc.cluster.local
        nodeIdBuilder.append(XdsConstant.SIDECAR)
                .append(XdsConstant.WAVY_LINE)
                .append(NetworkUtils.getMachineIp())
                .append(XdsConstant.WAVY_LINE)
                .append(System.getenv(XdsConstant.POD_NAME_ENV))
                .append(XdsConstant.POINT)
                .append(namespace)
                .append(XdsConstant.WAVY_LINE)
                .append(namespace)
                .append(XdsConstant.POINT)
                .append(XdsConstant.HOST_SUFFIX);
        this.node = Node.newBuilder()
                .setId(nodeIdBuilder.toString())
                .build();
    }

    /**
     * get response StreamObserver
     *
     * @param requestKey requestStreamObserver cache key
     * @param countDownLatch Used to notify the xds requesting thread to obtain data
     * @return StreamObserver
     */
    protected StreamObserver<DiscoveryResponse> getResponseStreamObserver(String requestKey,
            CountDownLatch countDownLatch) {
        return new StreamObserver<DiscoveryResponse>() {
            @Override
            public void onNext(DiscoveryResponse response) {
                handleResponse(requestKey, response);
                if (countDownLatch != null) {
                    countDownLatch.countDown();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (countDownLatch != null) {
                    countDownLatch.countDown();
                }
                initExecutor();
                executor.submit(() -> {
                    try {
                        Thread.sleep(DELAY_TIME);
                    } catch (InterruptedException e) {
                        LOGGER.log(Level.WARNING, "An error occurred in thread sleeping.", e);
                    }
                    client.updateChannel();
                    subscribe(requestKey, null);
                });
                LOGGER.log(Level.SEVERE, "An error occurred in Xds communication with istiod.", throwable);
            }

            @Override
            public void onCompleted() {
                if (countDownLatch != null) {
                    countDownLatch.countDown();
                }
                subscribe(requestKey, null);
                LOGGER.log(Level.WARNING, "Xds stream is closed, new stream has been created for communication.");
            }
        };
    }

    /**
     * handle response from istiod
     *
     * @param requestKey resource key to get the request observer from cache
     * @param response
     */
    protected abstract void handleResponse(String requestKey, DiscoveryResponse response);

    private static void initExecutor() {
        if (executor == null) {
            synchronized (XdsHandler.class) {
                if (executor == null) {
                    executor = new ThreadPoolExecutor(THREAD_CORE_SIZE, THREAD_MAX_SIZE, THREAD_KEEP_ALIVE_TIME,
                            TimeUnit.SECONDS, new ArrayBlockingQueue<>(THREAD_QUEUE_CAPACITY),
                            new NamedThreadFactory());
                    executor.allowCoreThreadTimeOut(true);
                }
            }
        }
    }

    /**
     * thread factory to name thread
     *
     * @author daizhenyu
     * @since 2024-06-25
     **/
    static class NamedThreadFactory implements ThreadFactory {
        /**
         * thread name prefix
         */
        private static final String THREAD_NAME_PREFIX = "xds-reconnection-thread-";

        /**
         * thread number
         */
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, THREAD_NAME_PREFIX + threadNumber.getAndIncrement());
        }
    }
}