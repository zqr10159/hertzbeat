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

package org.apache.hertzbeat.ai.tools.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.hertzbeat.common.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SshRecoveryToolsImplTest {

    @Test
    void shouldReturnFailureJsonWhenWorkingDirIsInvalid() {
        SshRecoveryToolsImpl tool = new SshRecoveryToolsImpl();
        ReflectionTestUtils.setField(tool, "demoMode", true);
        ReflectionTestUtils.setField(tool, "defaultTimeoutMs", 10_000);
        ReflectionTestUtils.setField(tool, "maxOutputSize", 12_000);
        ReflectionTestUtils.setField(tool, "commandAllowlist", "");

        String result = tool.executeSshRecovery(
                "127.0.0.1",
                22,
                "demo",
                "password",
                null,
                "echo ok",
                null,
                "bad'dir",
                null,
                null
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = JsonUtil.fromJson(result, Map.class);
        assertThat(resultMap.get("status")).isEqualTo("FAILED");
        assertThat(resultMap.get("command")).isEqualTo("echo ok");
        assertThat(resultMap.get("executedCommand")).isEqualTo("echo ok");
        assertThat(String.valueOf(resultMap.get("error"))).contains("workingDir cannot contain single quotes");
    }
}
