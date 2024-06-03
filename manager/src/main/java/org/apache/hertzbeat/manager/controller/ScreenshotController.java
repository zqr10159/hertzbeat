package org.apache.hertzbeat.manager.controller;

import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.net.http.WebSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@ServerEndpoint("/ws")
@Component
public class ScreenshotController {
    private Session session;

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        log.info("与SessionId：{}建立连接", session.getId());
    }

    @OnClose
    public void onClose() {
        log.info("WebSocket连接关闭");
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        log.info("来自SessionId:{}的消息:{}", session.getId(), message);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("Session:{}的WebSocket发生错误", session.getId(), error);
    }

    public Session getSession() {
        return session;
    }

    public String getSessionId() {
        return session.getId();
    }
}
