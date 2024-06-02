package org.apache.hertzbeat.manager.controller;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;


@Slf4j
@Controller
public class ScreenshotController {
    @MessageMapping("/send")
    @SendTo("/topic/messages")
    public Message sendMessage(Message message) {
        return new Message("Hello, WebSocket!", "System");
    }
    @Data
    @AllArgsConstructor
    public static class Message {
        private String content;
        private String sender;
    }
}
