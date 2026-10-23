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

package org.apache.hertzbeat.alert.notice.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.apache.hertzbeat.alert.AlerterProperties;
import org.apache.hertzbeat.base.dao.GeneralConfigDao;
import org.apache.hertzbeat.common.entity.alerter.GroupAlert;
import org.apache.hertzbeat.common.entity.alerter.NoticeReceiver;
import org.apache.hertzbeat.common.entity.alerter.NoticeTemplate;
import org.apache.hertzbeat.common.entity.dto.MailServerConfig;
import org.apache.hertzbeat.common.entity.manager.GeneralConfig;
import org.apache.hertzbeat.common.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.client.RestTemplate;

/** Actual Spring dependency resolution; every mail transport is intercepted in-process. */
class OptionalMailSenderContextTest {

    @Test
    void startsWithoutAnyMailSenderBean() {
        try (AnnotationConfigApplicationContext context = context(null, mock(GeneralConfigDao.class))) {
            context.refresh();
            assertNotNull(context.getBean(EmailAlertNotifyHandlerImpl.class));
            assertEquals(0, context.getBeansOfType(JavaMailSenderImpl.class).size());
        }
    }

    @Test
    void usesExistingSenderWithYamlConfiguration() throws Exception {
        CapturingSender sender = new CapturingSender();
        try (AnnotationConfigApplicationContext context = context(sender, mock(GeneralConfigDao.class))) {
            context.refresh();
            context.getBean(EmailAlertNotifyHandlerImpl.class).send(receiver(), template(), new GroupAlert());
            assertNotNull(sender.sent);
            assertEquals("yaml.invalid", sender.getHost());
            assertEquals(2525, sender.getPort());
            assertEquals("yaml@example.invalid", sender.sent.getFrom()[0].toString());
            assertEquals("receiver@example.invalid", sender.sent.getAllRecipients()[0].toString());
        }
    }

    @Test
    void databaseConfigurationWorksWithoutSpringMailBean() throws Exception {
        GeneralConfigDao dao = mock(GeneralConfigDao.class);
        MailServerConfig settings = new MailServerConfig();
        settings.setEnable(true);
        settings.setEmailHost("database.invalid");
        settings.setEmailPort(2526);
        settings.setEmailUsername("database@example.invalid");
        settings.setEmailPassword("synthetic-test-only");
        settings.setEmailSsl(true);
        when(dao.findByType("email")).thenReturn(GeneralConfig.builder().content(JsonUtil.toJson(settings)).build());
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        try (MockedConstruction<JavaMailSenderImpl> construction = mockConstruction(JavaMailSenderImpl.class,
                (sender, initialization) -> {
                    when(sender.getJavaMailProperties()).thenReturn(new Properties());
                    when(sender.createMimeMessage()).thenReturn(message);
                });
                AnnotationConfigApplicationContext context = context(null, dao)) {
            context.refresh();
            context.getBean(EmailAlertNotifyHandlerImpl.class).send(receiver(), template(), new GroupAlert());
            assertEquals(1, construction.constructed().size());
            JavaMailSenderImpl sender = construction.constructed().getFirst();
            verify(sender).setHost("database.invalid");
            verify(sender).setPort(2526);
            verify(sender).send(message);
            assertEquals("database@example.invalid", message.getFrom()[0].toString());
            assertEquals("receiver@example.invalid", message.getAllRecipients()[0].toString());
        }
    }

    private static AnnotationConfigApplicationContext context(JavaMailSenderImpl sender, GeneralConfigDao dao) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (sender != null) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", java.util.Map.of(
                    "spring.mail.host", "yaml.invalid", "spring.mail.username", "yaml@example.invalid",
                    "spring.mail.password", "synthetic-test-only", "spring.mail.port", "2525")));
        }
        context.registerBean(GeneralConfigDao.class, () -> dao);
        context.registerBean(RestTemplate.class, () -> new RestTemplate());
        context.registerBean(AlerterProperties.class, AlerterProperties::new);
        if (sender != null) {
            context.registerBean(JavaMailSenderImpl.class, () -> sender);
        }
        context.register(EmailAlertNotifyHandlerImpl.class);
        return context;
    }

    private static NoticeReceiver receiver() {
        NoticeReceiver receiver = new NoticeReceiver();
        receiver.setEmail("receiver@example.invalid");
        return receiver;
    }

    private static NoticeTemplate template() {
        NoticeTemplate template = new NoticeTemplate();
        template.setContent("test notification");
        return template;
    }

    private static final class CapturingSender extends JavaMailSenderImpl {
        private MimeMessage sent;

        @Override
        public void send(MimeMessage message) {
            sent = message;
        }
    }
}
