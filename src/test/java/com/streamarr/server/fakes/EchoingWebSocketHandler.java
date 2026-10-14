package com.streamarr.server.fakes;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Stands in for the GraphQL WebSocket handler behind a decorator: it echoes each message it
 * receives and can send frames of its own, the way subscription events arrive.
 */
public class EchoingWebSocketHandler extends TextWebSocketHandler {

  private final AtomicReference<WebSocketSession> established = new AtomicReference<>();
  private final AtomicReference<WebSocketSession> transportErrorSession = new AtomicReference<>();
  private final List<TextMessage> receivedMessages = new CopyOnWriteArrayList<>();
  private final List<WebSocketSession> closedSessions = new CopyOnWriteArrayList<>();

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    established.set(session);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    receivedMessages.add(message);
    session.sendMessage(new TextMessage("echo:" + message.getPayload()));
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    transportErrorSession.set(session);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    closedSessions.add(session);
  }

  /** Sends a frame on the session this handler was given, as a subscription event does. */
  public void deliver(String payload) throws IOException {
    established.get().sendMessage(new TextMessage(payload));
  }

  public WebSocketSession establishedSession() {
    return established.get();
  }

  public WebSocketSession transportErrorSession() {
    return transportErrorSession.get();
  }

  public List<TextMessage> receivedMessages() {
    return List.copyOf(receivedMessages);
  }

  public List<WebSocketSession> closedSessions() {
    return List.copyOf(closedSessions);
  }
}
