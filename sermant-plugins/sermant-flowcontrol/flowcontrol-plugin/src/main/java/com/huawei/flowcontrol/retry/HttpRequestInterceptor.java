/*
 * Copyright (C) 2022-2022 Huawei Technologies Co., Ltd. All rights reserved.
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
 *
 */

package com.huawei.flowcontrol.retry;

import com.huawei.flowcontrol.common.entity.HttpRequestEntity;
import com.huawei.flowcontrol.common.handler.retry.AbstractRetry;
import com.huawei.flowcontrol.common.handler.retry.Retry;
import com.huawei.flowcontrol.common.handler.retry.RetryContext;
import com.huawei.flowcontrol.service.InterceptorSupporter;
import com.huawei.sermant.core.common.LoggerFactory;
import com.huawei.sermant.core.plugin.agent.entity.ExecuteContext;

import org.springframework.http.HttpRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * DispatcherServlet 的 API接口增强 埋点定义sentinel资源
 *
 * @author zhouss
 * @since 2022-02-11
 */
public class HttpRequestInterceptor extends InterceptorSupporter {
    private static final Logger LOGGER = LoggerFactory.getLogger();

    private final Retry retry = new HttpRetry();

    /**
     * http请求数据转换 适应plugin -> service数据传递 注意，该方法不可抽出，由于宿主依赖仅可由该拦截器加载，因此抽出会导致找不到类
     *
     * @param request 请求
     * @return HttpRequestEntity
     */
    private Optional<HttpRequestEntity> convertToHttpEntity(HttpRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        return Optional.of(new HttpRequestEntity(request.getURI().getPath(), request.getHeaders().toSingleValueMap(),
            request.getMethod().name()));
    }

    @Override
    protected final ExecuteContext doBefore(ExecuteContext context) {
        return context;
    }

    @Override
    protected final ExecuteContext doAfter(ExecuteContext context) {
        final Object[] allArguments = context.getArguments();
        final HttpRequest request = (HttpRequest) context.getObject();
        Object result = context.getResult();
        try {
            final Optional<HttpRequestEntity> httpRequestEntity = convertToHttpEntity(request);
            if (!httpRequestEntity.isPresent()) {
                return context;
            }
            RetryContext.INSTANCE.markRetry(retry);
            final List<io.github.resilience4j.retry.Retry> handlers = getRetryHandler()
                .getHandlers(httpRequestEntity.get());
            if (!handlers.isEmpty() && needRetry(handlers.get(0), result, null)) {
                // 重试仅有一个策略
                request.getHeaders().add(RETRY_KEY, RETRY_VALUE);
                result = handlers.get(0).executeCheckedSupplier(() -> {
                    final Supplier<Object> retryFunc = createRetryFunc(context.getObject(),
                        context.getMethod(), allArguments, context.getResult());
                    return retryFunc.get();
                });
                request.getHeaders().remove(RETRY_KEY);
            }
        } catch (Throwable throwable) {
            LOGGER.warning(String.format(Locale.ENGLISH,
                "Failed to invoke method:%s for few times, reason:%s",
                context.getMethod().getName(), throwable.getCause()));
        } finally {
            RetryContext.INSTANCE.removeRetry();
        }
        context.changeResult(result);
        return context;
    }

    /**
     * Http请求重试
     *
     * @since 2022-02-21
     */
    public static class HttpRetry extends AbstractRetry {
        private static final String METHOD_KEY = "ClientHttpResponse#getRawStatusCode";

        @Override
        protected Optional<String> getCode(Object result) {
            final Optional<Method> getRawStatusCode = getInvokerMethod(METHOD_KEY, fn -> {
                try {
                    final Method method = result.getClass().getDeclaredMethod("getRawStatusCode");
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ex) {
                    LOGGER.warning(String.format(Locale.ENGLISH,
                        "Can not find method getRawStatusCode from response class %s",
                        result.getClass().getName()));
                }
                return placeHolderMethod;
            });
            if (!getRawStatusCode.isPresent()) {
                return Optional.empty();
            }
            try {
                return Optional.of(String.valueOf(getRawStatusCode.get().invoke(result)));
            } catch (IllegalAccessException ex) {
                LOGGER.warning(String.format(Locale.ENGLISH,
                    "Can not find method getRawStatusCode from class [%s]!",
                    result.getClass().getCanonicalName()));
            } catch (InvocationTargetException ex) {
                LOGGER.warning(String.format(Locale.ENGLISH, "Invoking method getRawStatusCode failed, reason: %s",
                    ex.getMessage()));
            }
            return Optional.empty();
        }

        @Override
        public Class<? extends Throwable>[] retryExceptions() {
            return getRetryExceptions();
        }

        @Override
        public RetryFramework retryType() {
            return RetryFramework.SPRING_CLOUD;
        }
    }
}
