package com.streamarr.server.fakes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/** An open server-side session that keeps every frame sent to the client and its close status. */
public class FakeWebSocketSession implements WebSocketSession {

  private final String id = UUID.randomUUID().toString();
  private final Map<String, Object> attributes = new ConcurrentHashMap<>();
  private final List<WebSocketMessage<?>> sentMessages = new CopyOnWriteArrayList<>();
  private final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
  private final AtomicInteger closeRequests = new AtomicInteger();
  private volatile boolean closeFails;

  public FakeWebSocketSession(Map<String, Object> attributes) {
    this.attributes.putAll(attributes);
  }

  public List<WebSocketMessage<?>> sentMessages() {
    return List.copyOf(sentMessages);
  }

  public Optional<CloseStatus> closeStatus() {
    return Optional.ofNullable(closeStatus.get());
  }

  /** How many times anything asked the session to close. */
  public int closeRequests() {
    return closeRequests.get();
  }

  /** Makes every later close fail the way a broken connection does. */
  public void failCloses() {
    closeFails = true;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public URI getUri() {
    return URI.create("ws://localhost/graphql");
  }

  @Override
  public HttpHeaders getHandshakeHeaders() {
    return new HttpHeaders();
  }

  @Override
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  @Override
  public Principal getPrincipal() {
    return null;
  }

  @Override
  public InetSocketAddress getLocalAddress() {
    return null;
  }

  @Override
  public InetSocketAddress getRemoteAddress() {
    return null;
  }

  @Override
  public String getAcceptedProtocol() {
    return "graphql-transport-ws";
  }

  @Override
  public void setTextMessageSizeLimit(int messageSizeLimit) {
    // The fake accepts messages of any size.
  }

  @Override
  public int getTextMessageSizeLimit() {
    return Integer.MAX_VALUE;
  }

  @Override
  public void setBinaryMessageSizeLimit(int messageSizeLimit) {
    // The fake accepts messages of any size.
  }

  @Override
  public int getBinaryMessageSizeLimit() {
    return Integer.MAX_VALUE;
  }

  @Override
  public List<WebSocketExtension> getExtensions() {
    return List.of();
  }

  @Override
  public void sendMessage(WebSocketMessage<?> message) {
    if (!isOpen()) {
      throw new IllegalStateException("The session is closed.");
    }

    sentMessages.add(message);
  }

  @Override
  public boolean isOpen() {
    return closeStatus.get() == null;
  }

  @Override
  public void close() throws IOException {
    close(CloseStatus.NORMAL);
  }

  @Override
  public void close(CloseStatus status) throws IOException {
    closeRequests.incrementAndGet();
    if (closeFails) {
      throw new IOException("The connection is already broken.");
    }

    closeStatus.compareAndSet(null, status);
  }
}
