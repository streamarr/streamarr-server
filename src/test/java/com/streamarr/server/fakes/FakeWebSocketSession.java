package com.streamarr.server.fakes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * An open server-side session that keeps every frame written to the client and its close status.
 * Like a container session, it keeps the first close status and ignores later closes.
 */
public class FakeWebSocketSession implements WebSocketSession {

  private static final Duration BOUND = Duration.ofSeconds(5);

  private final String id = UUID.randomUUID().toString();
  private final Map<String, Object> attributes = new ConcurrentHashMap<>();
  private final List<WebSocketMessage<?>> sentMessages = new CopyOnWriteArrayList<>();
  private final CompletableFuture<CloseStatus> closeStatus = new CompletableFuture<>();
  private final CountDownLatch writeStarted = new CountDownLatch(1);
  private final CountDownLatch writesReleased = new CountDownLatch(1);
  private volatile boolean closeFails;
  private volatile boolean writesBlocked;
  private volatile boolean writesFail;

  public FakeWebSocketSession(Map<String, Object> attributes) {
    this.attributes.putAll(attributes);
  }

  public List<WebSocketMessage<?>> sentMessages() {
    return List.copyOf(sentMessages);
  }

  public Optional<CloseStatus> closeStatus() {
    return Optional.ofNullable(closeStatus.getNow(null));
  }

  /** The close status, waiting for a close requested on another thread. */
  public CloseStatus awaitCloseStatus() throws Exception {
    return closeStatus.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Makes every later write fail the way a broken connection does. */
  public void failWrites() {
    writesFail = true;
  }

  /** Makes the next write wait until {@link #releaseWrites()}, the way a stalled client does. */
  public void blockWrites() {
    writesBlocked = true;
  }

  public void awaitBlockedWrite() throws InterruptedException {
    if (!writeStarted.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new AssertionError("No write started within " + BOUND);
    }
  }

  public void releaseWrites() {
    writesReleased.countDown();
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
  public void sendMessage(WebSocketMessage<?> message) throws IOException {
    if (!isOpen()) {
      throw new IllegalStateException("The session is closed.");
    }

    if (writesFail) {
      throw new IOException("The connection is broken.");
    }

    if (writesBlocked) {
      writeStarted.countDown();
      awaitWritesReleased();
    }

    sentMessages.add(message);
  }

  private void awaitWritesReleased() throws IOException {
    try {
      if (!writesReleased.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IOException("The blocked write timed out.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while the write was blocked.", e);
    }
  }

  @Override
  public boolean isOpen() {
    return !closeStatus.isDone();
  }

  @Override
  public void close() throws IOException {
    close(CloseStatus.NORMAL);
  }

  @Override
  public void close(CloseStatus status) throws IOException {
    if (closeFails) {
      throw new IOException("The connection is already broken.");
    }

    closeStatus.complete(status);
  }
}
