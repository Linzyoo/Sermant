/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2021-2021. All rights reserved
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

package com.huawei.javamesh.core.agent.definition;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * 方法拦截点
 */
public class MethodInterceptPoint {

    private final String interceptor;

    private final ElementMatcher<MethodDescription> methodMatcher;

    private final MethodType methodType;

    private MethodInterceptPoint(String interceptor, ElementMatcher<MethodDescription> methodMatcher, MethodType methodType) {
        this.interceptor = interceptor;
        this.methodMatcher = methodMatcher;
        this.methodType = methodType;
    }

    public static MethodInterceptPoint newInstMethodInterceptPoint(String interceptor,
            ElementMatcher<MethodDescription> methodMatcher) {
        return new MethodInterceptPoint(interceptor, methodMatcher, MethodType.INSTANCE);
    }

    public static MethodInterceptPoint newStaticMethodInterceptPoint(String interceptor,
            ElementMatcher<MethodDescription> methodMatcher) {
        return new MethodInterceptPoint(interceptor, methodMatcher, MethodType.STATIC);
    }

    public static MethodInterceptPoint newConstructorInterceptPoint(String interceptor,
            ElementMatcher<MethodDescription> methodMatcher) {
        return new MethodInterceptPoint(interceptor, methodMatcher, MethodType.CONSTRUCTOR);
    }

    public String getInterceptor() {
        return interceptor;
    }

    public ElementMatcher<MethodDescription> getMatcher() {
        return methodMatcher;
    }

    public boolean isInstanceMethod() {
        return MethodType.INSTANCE == this.methodType;
    }

    public boolean isStaticMethod() {
        return MethodType.STATIC == this.methodType;
    }

    public boolean isConstructor() {
        return MethodType.CONSTRUCTOR == this.methodType;
    }

    private enum MethodType {
        INSTANCE,
        STATIC,
        CONSTRUCTOR
    }
}
