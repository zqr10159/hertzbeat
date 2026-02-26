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

package org.apache.hertzbeat.common.entity.job.param;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Monitoring parameter definitions
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ParamDefine {

    /**
     * Parameter Structure ID
     */
    private Long id;

    /**
     * Monitoring application type name
     */
    private String app;

    /**
     * Parameter field external display name
     * Port
     */
    private Map<String, String> name;

    /**
     * Parameter Field Identifier
     */
    private String field;

    /**
     * Field type, style (mostly map the input tag type attribute)
     */
    private String type;

    /**
     * Is it mandatory true-required false-optional
     */
    private boolean required = false;

    /**
     * Parameter Default Value
     */
    private String defaultValue;

    /**
     * Parameter input box prompt information
     */
    private String placeholder;

    /**
     * When type is number, use range to represent the range eg: 0-233
     */
    private String range;

    /**
     * When type is text, use limit to indicate the limit size of the string. The maximum is 255
     */
    private Short limit;

    /**
     * When the type is radio radio box, checkbox checkbox, options represents a list of optional values
     * eg: {
     * "key1":"value1",
     * "key2":"value2"
     * }
     * key-Value display label
     * value-True value
     */
    private List<Option> options;

    /**
     * Valid when type is key-value, indicating the alias description of the key
     */
    private String keyAlias;

    /**
     * Valid when type is key-value, indicating the alias description of value type
     */
    private String valueAlias;

    /**
     * Is it an advanced hidden parameter true-yes false-no
     */
    private boolean hide = false;

    /**
     * The creator of this record
     */
    private String creator;

    /**
     * This record was last modified by
     */
    private String modifier;

    /**
     *  Depends on which parameters
     */
    private Map<String, List<Object>> depend;

    /**
     * Parameter option configuration
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static final class Option {
        /**
         * value display label
         */
        private String label;
        /**
         * optional value
         */
        private String value;
    }
}
