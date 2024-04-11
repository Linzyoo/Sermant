/*
 * Copyright (C) 2023-2023 Huawei Technologies Co., Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.discovery.entity;

import com.huawei.discovery.config.LbConfig;
import com.huawei.discovery.retry.config.DefaultRetryConfig;
import com.huawei.discovery.retry.config.RetryConfig;

import com.huaweicloud.sermant.core.common.LoggerFactory;
import com.huaweicloud.sermant.core.plugin.config.PluginConfigManager;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import org.springframework.web.reactive.function.client.ClientResponse;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * webclient Retry
 *
 * @author provenceee
 * @since 2023-05-05
 */
public class RetryExchangeFilterFunction extends AbstractRetryExchangeFilterFunction {
    private static final Logger LOGGER = LoggerFactory.getLogger();

    private static final RetryConfig RETRY_CONFIG = DefaultRetryConfig.create();

    private static final LbConfig LB_CONFIG = PluginConfigManager.getPluginConfig(LbConfig.class);

    private volatile AbstractRetryExchangeFilterFunction exchangeFilterFunction;

    @Override
    public Mono<ClientResponse> retry(Mono<ClientResponse> mono) {
        try {
            return mono.retryWhen(
                    Retry.fixedDelay(LB_CONFIG.getMaxRetry(), Duration.ofMillis(LB_CONFIG.getRetryWaitMs()))
                            .filter(throwable -> {
                                boolean shouldRetry = RETRY_CONFIG.getThrowablePredicate().test(throwable);
                                if (shouldRetry) {
                                    LOGGER.log(Level.WARNING, "Start retry, throwable is: ", throwable);
                                }
                                return shouldRetry;
                            }));
        } catch (NoSuchMethodError | NoClassDefFoundError error) {
            LOGGER.log(Level.SEVERE, "Cannot not use RetryExchangeFilterFunction, "
                    + "will try to use LowerVersionRetryExchangeFilterFunction.");
            return getLowerVersionRetryExchangeFilterFunction().retry(mono);
        }
    }

    private AbstractRetryExchangeFilterFunction getLowerVersionRetryExchangeFilterFunction() {
        if (exchangeFilterFunction == null) {
            synchronized (this) {
                if (exchangeFilterFunction == null) {
                    exchangeFilterFunction = new LowerVersionRetryExchangeFilterFunction();
                }
            }
        }
        return exchangeFilterFunction;
    }
}