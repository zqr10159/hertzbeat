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

package org.apache.hertzbeat.collector.engine;

import lombok.Builder;
import lombok.Data;

import java.util.concurrent.Executor;

/**
 * Runtime configuration for a {@link CollectorEngine} instance.
 *
 * <p>All fields mirror the existing {@code collector.dispatch.entrance.netty.*}
 * configuration keys so that the user-facing YAML configuration does not need to
 * change.
 */
@Data
@Builder
public class CollectorRuntimeConfig {

    /**
     * Whether the Netty-based entrance is enabled (standalone / cluster mode).
     * When {@code false} the engine runs in embedded mode inside the manager.
     */
    private boolean nettyEnabled;

    /**
     * Unique identity of this collector node.
     * Defaults to {@code <hostname>-collector} when not set.
     */
    private String identity;

    /**
     * Collector operating mode, e.g. {@code public} or {@code private}.
     */
    private String mode;

    /**
     * Hostname or IP address of the HertzBeat manager to connect to
     * (only relevant when {@link #nettyEnabled} is {@code true}).
     */
    private String managerHost;

    /**
     * Port of the HertzBeat manager's Netty server
     * (only relevant when {@link #nettyEnabled} is {@code true}).
     */
    private int managerPort;

    /**
     * Optional custom {@link Executor} used to run collection tasks.
     * When {@code null} the engine uses its own internal thread pool.
     * Inject {@code Executors.newVirtualThreadPerTaskExecutor()} here to
     * enable virtual-thread-based collection on JDK 21+.
     */
    private Executor taskExecutor;
}
