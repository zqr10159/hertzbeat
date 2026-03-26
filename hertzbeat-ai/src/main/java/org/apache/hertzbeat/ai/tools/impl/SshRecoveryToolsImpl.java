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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.ai.tools.SshRecoveryTools;
import org.apache.hertzbeat.collector.collect.common.ssh.CommonSshBlacklist;
import org.apache.hertzbeat.collector.collect.common.ssh.SshHelper;
import org.apache.hertzbeat.common.util.JsonUtil;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Implementation of SSH recovery execution tools.
 */
@Slf4j
@Service
public class SshRecoveryToolsImpl implements SshRecoveryTools {

    private static final int DEFAULT_SSH_PORT = 22;
    private static final int MIN_TIMEOUT_MS = 1_000;
    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_MAX_OUTPUT_SIZE = 12_000;
    private static final int MAX_STD_BUFFER_LIMIT = 1024 * 1024;

    @Value("${hertzbeat.ai.ssh.demoMode:false}")
    private boolean demoMode;

    @Value("${hertzbeat.ai.ssh.timeoutMs:" + DEFAULT_TIMEOUT_MS + "}")
    private int defaultTimeoutMs;

    @Value("${hertzbeat.ai.ssh.maxOutputSize:" + DEFAULT_MAX_OUTPUT_SIZE + "}")
    private int maxOutputSize;

    @Value("${hertzbeat.ai.ssh.commandAllowlist:}")
    private String commandAllowlist;

    @Override
    @Tool(name = "ssh_execute_recovery",
          description = "Execute an SSH recovery command on a remote host. "
                  + "This tool returns command execution result including exitCode/stdout/stderr/durationMs/executedAt.")
    public String executeSshRecovery(
            @ToolParam(description = "Target host/IP for SSH", required = true) String host,
            @ToolParam(description = "SSH port, default 22", required = false) Integer port,
            @ToolParam(description = "SSH username", required = true) String username,
            @ToolParam(description = "SSH password, optional when privateKeyPath is provided", required = false) String password,
            @ToolParam(description = "Local private key file path, optional when password is provided", required = false) String privateKeyPath,
            @ToolParam(description = "Command to execute", required = true) String command,
            @ToolParam(description = "Command timeout in ms, default from hertzbeat.ai.ssh.timeoutMs", required = false) Integer timeoutMs,
            @ToolParam(description = "Optional working directory for command execution", required = false) String workingDir,
            @ToolParam(description = "Correlation id for logs and evidence", required = false) String correlationId,
            @ToolParam(description = "Additional description for this execution", required = false) String description) {

        String resolvedCorrelationId = sanitizeCorrelationId(correlationId);
        long startTime = System.currentTimeMillis();

        try {
            String resolvedHost = sanitizeHost(host);
            int resolvedPort = resolvePort(port);
            String resolvedUsername = sanitizeRequired(username, "username");
            String resolvedCommand = sanitizeRequired(command, "command");
            int resolvedTimeout = resolveTimeout(timeoutMs);
            String resolvedWorkingDir = org.springframework.util.StringUtils.hasText(workingDir) ? workingDir.trim() : null;
            String resolvedDescription = org.springframework.util.StringUtils.hasText(description)
                    ? description.trim()
                    : "Recovery action executed by AI workflow";
            validateCredentials(password, privateKeyPath);
            validateCommand(resolvedCommand);

            String privateKeyContent = resolvePrivateKey(password, privateKeyPath);
            String executedCommand = buildExecutedCommand(resolvedCommand, resolvedWorkingDir);

            ClientSession clientSession = null;
            ClientChannel channel = null;
            try {
                clientSession = SshHelper.getConnectSession(
                        resolvedHost,
                        String.valueOf(resolvedPort),
                        resolvedUsername,
                        org.springframework.util.StringUtils.hasText(password) ? password : null,
                        privateKeyContent,
                        null,
                        resolvedTimeout,
                        false);

                channel = clientSession.createExecChannel(executedCommand);
                ByteArrayOutput stdout = new ByteArrayOutput();
                ByteArrayOutput stderr = new ByteArrayOutput();
                channel.setOut(stdout);
                channel.setErr(stderr);
                channel.open().verify(resolvedTimeout);

                List<ClientChannelEvent> waitEvents = new ArrayList<>();
                waitEvents.add(ClientChannelEvent.CLOSED);
                if (channel.waitFor(waitEvents, resolvedTimeout).contains(ClientChannelEvent.TIMEOUT)) {
                    sendCancelSignal(channel);
                    throw new IllegalArgumentException("SSH command execution timeout: " + resolvedTimeout + "ms");
                }

                int exitCode = resolveExitCode(channel.getExitStatus());
                long durationMs = System.currentTimeMillis() - startTime;

                Map<String, Object> result = buildResult(
                        resolvedCorrelationId,
                        resolvedHost,
                        resolvedPort,
                        resolvedUsername,
                        resolvedCommand,
                        executedCommand,
                        resolvedWorkingDir,
                        resolvedDescription,
                        exitCode,
                        trimOutput(stdout.toString(StandardCharsets.UTF_8)),
                        trimOutput(stderr.toString(StandardCharsets.UTF_8)),
                        durationMs,
                        null,
                        true
                );

                log.info("SSH recovery executed, correlationId={}, host={}:{}, exitCode={}, durationMs={}",
                        resolvedCorrelationId, resolvedHost, resolvedPort, exitCode, durationMs);
                return JsonUtil.toJson(result);
            } finally {
                closeChannel(channel);
                closeSession(clientSession);
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            Map<String, Object> result = buildResult(
                resolvedCorrelationId,
                host,
                safeResolvePort(port),
                    username,
                    safeTrimOrEmpty(command),
                    safeBuildExecutedCommand(safeTrimOrEmpty(command), workingDir),
                    org.springframework.util.StringUtils.hasText(workingDir) ? workingDir.trim() : null,
                    org.springframework.util.StringUtils.hasText(description) ? description.trim() : "Recovery action executed by AI workflow",
                    -1,
                    "",
                    "",
                    durationMs,
                    e.getMessage(),
                    false
            );
            log.error("SSH recovery execution failed, correlationId={}, error={}", resolvedCorrelationId, e.getMessage(), e);
            return JsonUtil.toJson(result);
        }
    }

    private String sanitizeHost(String host) {
        return sanitizeRequired(host, "host");
    }

    private String sanitizeRequired(String value, String fieldName) {
        if (!org.springframework.util.StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Missing required parameter: " + fieldName);
        }
        return value.trim();
    }

    private String safeTrimOrEmpty(String value) {
        return org.springframework.util.StringUtils.hasText(value) ? value.trim() : "";
    }

    private int resolvePort(Integer port) {
        if (port == null) {
            return DEFAULT_SSH_PORT;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port + ", expected range 1-65535");
        }
        return port;
    }

    private int safeResolvePort(Integer port) {
        try {
            return resolvePort(port);
        } catch (IllegalArgumentException e) {
            return DEFAULT_SSH_PORT;
        }
    }

    private int resolveTimeout(Integer timeoutMs) {
        if (timeoutMs == null) {
            return Math.max(MIN_TIMEOUT_MS, defaultTimeoutMs);
        }
        return Math.max(MIN_TIMEOUT_MS, timeoutMs);
    }

    private int resolveMaxOutputSize() {
        int size = maxOutputSize > 0 ? maxOutputSize : DEFAULT_MAX_OUTPUT_SIZE;
        return Math.min(Math.max(256, size), MAX_STD_BUFFER_LIMIT);
    }

    private void validateCredentials(String password, String privateKeyPath) {
        boolean hasPassword = org.springframework.util.StringUtils.hasText(password);
        boolean hasPrivateKey = org.springframework.util.StringUtils.hasText(privateKeyPath);
        if (!hasPassword && !hasPrivateKey) {
            throw new IllegalArgumentException("Either password or privateKeyPath is required");
        }
        if (hasPassword && hasPrivateKey) {
            log.warn("Both password and private key are provided; private key will not be used");
        }
    }

    private void validateCommand(String command) {
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (demoMode) {
            log.info("Demo mode enabled, skipping command blacklist and allowlist.");
            return;
        }

        if (StringUtils.isNotBlank(commandAllowlist)) {
            List<String> allowlist = parseRuleList(commandAllowlist);
            boolean allow = allowlist.stream().anyMatch(normalized::startsWith);
            if (!allow) {
                throw new IllegalArgumentException("Command not in allowlist: " + command);
            }
            return;
        }

        if (CommonSshBlacklist.isCommandBlacklisted(command)) {
            throw new IllegalArgumentException("Command is blacklisted: " + command);
        }
    }

    private List<String> parseRuleList(String value) {
        List<String> list = Arrays.stream(value.split(","))
                .map(String::trim)
                .map(item -> item.toLowerCase(Locale.ROOT))
                .filter(org.springframework.util.StringUtils::hasText)
                .toList();
        return list;
    }

    private String resolvePrivateKey(String password, String privateKeyPath) {
        if (org.springframework.util.StringUtils.hasText(password)) {
            return null;
        }
        if (!org.springframework.util.StringUtils.hasText(privateKeyPath)) {
            return null;
        }
        try {
            Path keyPath = Paths.get(privateKeyPath.trim());
            if (!Files.exists(keyPath)) {
                throw new IllegalArgumentException("privateKeyPath not exists: " + privateKeyPath);
            }
            return Files.readString(keyPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read private key: " + e.getMessage(), e);
        }
    }

    private String safeBuildExecutedCommand(String command, String workingDir) {
        try {
            return buildExecutedCommand(command, workingDir);
        } catch (IllegalArgumentException e) {
            return command;
        }
    }

    private String buildExecutedCommand(String command, String workingDir) {
        if (!org.springframework.util.StringUtils.hasText(workingDir)) {
            return command;
        }
        String normalizedWorkingDir = workingDir.trim();
        if (normalizedWorkingDir.contains("'")) {
            throw new IllegalArgumentException("workingDir cannot contain single quotes in this demo tool");
        }
        return "cd '" + normalizedWorkingDir + "' && " + command;
    }

    private String sanitizeCorrelationId(String correlationId) {
        if (org.springframework.util.StringUtils.hasText(correlationId)) {
            return correlationId.trim();
        }
        return UUID.randomUUID().toString();
    }

    private int resolveExitCode(Integer exitStatus) {
        return exitStatus == null ? -1 : exitStatus;
    }

    private String trimOutput(String output) {
        if (output == null) {
            return "";
        }
        int limit = resolveMaxOutputSize();
        if (output.length() <= limit) {
            return output;
        }
        int truncatedCount = output.length() - limit;
        return output.substring(0, limit) + "\n... output truncated, omitted " + truncatedCount + " chars ...";
    }

    private Map<String, Object> buildResult(String correlationId, String host, int port, String username,
                                           String command, String executedCommand, String workingDir,
                                           String description, int exitCode, String stdout, String stderr,
                                           long durationMs, String error, boolean success) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", success ? "SUCCESS" : "FAILED");
        result.put("correlationId", correlationId);
        result.put("host", host);
        result.put("port", port);
        result.put("username", username);
        result.put("command", command);
        result.put("executedCommand", executedCommand);
        result.put("workingDir", workingDir);
        result.put("exitCode", exitCode);
        result.put("stdout", stdout);
        result.put("stderr", stderr);
        result.put("durationMs", durationMs);
        result.put("executedAt", OffsetDateTime.now().toString());
        result.put("description", description);
        result.put("remark", description);
        result.put("maxOutputSize", resolveMaxOutputSize());
        if (StringUtils.isNotBlank(error)) {
            result.put("error", error);
        }
        return result;
    }

    private void closeChannel(ClientChannel channel) {
        if (channel == null || !channel.isOpen()) {
            return;
        }
        try {
            channel.close();
        } catch (Exception e) {
            log.warn("Failed to close SSH channel: {}", e.getMessage());
        }
    }

    private void closeSession(ClientSession clientSession) {
        if (clientSession == null || !clientSession.isOpen()) {
            return;
        }
        try {
            clientSession.close();
        } catch (Exception e) {
            log.warn("Failed to close SSH session: {}", e.getMessage());
        }
    }

    private void sendCancelSignal(ClientChannel channel) {
        try {
            channel.getInvertedIn().write(3);
            channel.getInvertedIn().flush();
        } catch (IOException e) {
            log.warn("Failed to send cancel signal: {}", e.getMessage());
        }
    }

    /**
     * Thin wrapper for command output with size limit control.
     */
    private static class ByteArrayOutput extends java.io.ByteArrayOutputStream {
        @Override
        public synchronized String toString(java.nio.charset.Charset charset) {
            byte[] data = toByteArray();
            if (data.length == 0) {
                return "";
            }
            if (data.length > MAX_STD_BUFFER_LIMIT) {
                byte[] truncated = Arrays.copyOf(data, MAX_STD_BUFFER_LIMIT);
                return new String(truncated, charset);
            }
            return new String(data, charset);
        }
    }
}
