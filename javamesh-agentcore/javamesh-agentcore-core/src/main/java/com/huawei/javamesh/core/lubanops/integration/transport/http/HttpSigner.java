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

package com.huawei.javamesh.core.lubanops.integration.transport.http;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import com.huawei.javamesh.core.lubanops.bootstrap.config.AgentConfigManager;
import com.huawei.javamesh.core.lubanops.integration.utils.BinaryUtils;

/**
 * @author
 * @date 2020/8/7 15:11
 */
public class HttpSigner extends AbstractHttpSinger {

    public static final String TS = "apm2ts";

    public static final String AK = "apm2ak";

    public static final String SIG = "apm2sig";

    @Override
    public void sign(Request request) throws InvalidKeyException, NoSuchAlgorithmException {
        HttpRequest httpRequest = (HttpRequest) request;
        String singerDate = this.getHeader(httpRequest, TS);
        if (singerDate == null) {
            SimpleDateFormat sdf = new SimpleDateFormat(TIME_FORMATTER);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            singerDate = sdf.format(new Date());
            httpRequest.addHeader(TS, singerDate);
        }
        httpRequest.addHeader(AK, AgentConfigManager.getMasterAuthAk());
        byte[] signingKey = this.deriveSigningKey(AgentConfigManager.getMasterAuthSk());
        byte[] signature = this.computeSignature(singerDate, signingKey);
        httpRequest.setSignature(BinaryUtils.toHex(signature));

    }

}
