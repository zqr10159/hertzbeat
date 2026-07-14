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

package org.apache.hertzbeat.observability.ingestion.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.apache.hertzbeat.observability.ingestion.storage.OtlpSignalStorage;
import org.junit.jupiter.api.Test;

class OtlpGrpcIngestionServiceStorageBoundaryTest {

    @Test
    void applicationServiceDependsOnlyOnTheStorageNeutralGateway() {
        Field[] fields = OtlpGrpcIngestionServiceImpl.class.getDeclaredFields();

        assertTrue(Arrays.stream(fields).anyMatch(field -> field.getType() == OtlpSignalStorage.class));
        assertFalse(Arrays.stream(fields).map(Field::getType).map(Class::getName)
                .anyMatch(type -> type.contains("greptime")
                        || type.endsWith("GreptimeOtlpForwarder")
                        || type.endsWith("RestTemplate")
                        || type.endsWith("OtlpIngestionRetryService")));
    }
}
