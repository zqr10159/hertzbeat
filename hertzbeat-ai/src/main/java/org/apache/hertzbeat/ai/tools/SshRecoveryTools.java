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

package org.apache.hertzbeat.ai.tools;

/**
 * Tools for SSH-based business recovery execution.
 */
public interface SshRecoveryTools {

    /**
     * Execute an SSH command for recovery on a target host.
     *
     * @param host SSH host
     * @param port SSH port
     * @param username SSH username
     * @param password SSH password (required when privateKeyPath is not set)
     * @param privateKeyPath Local path of private key file (optional)
     * @param command Command to execute
     * @param timeoutMs Command timeout in milliseconds
     * @param workingDir Working directory for command execution (optional)
     * @param correlationId External correlation id for logs and evidence
     * @param description Additional remark for audit
     * @return JSON string result: includes exitCode/stdout/stderr/durationMs/executedAt/remark
     */
    String executeSshRecovery(String host, Integer port, String username, String password, String privateKeyPath,
                              String command, Integer timeoutMs, String workingDir, String correlationId,
                              String description);
}

