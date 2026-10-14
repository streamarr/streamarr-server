package com.streamarr.server.graphql.transport;

import io.micrometer.core.instrument.Counter;
import jakarta.websocket.Session;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.websocket.Constants;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

/**
 * Bounds the frames a connection holds for its client (ADR 0042). The GraphQL handler hands each
 * frame over without waiting; a writer on the send executor writes them in order. When a client
 * stops reading, its unsent frames grow. The connection closes with {@link #CLIENT_FELL_BEHIND} as
 * soon as they would exceed the configured capacity, whatever the frames are: results, refusals or
 * protocol messages.
 */
@Slf4j
class SendBufferedWebSocketHandler extends WebSocketHandlerDecorator {

  /** A private close code outside the graphql-transport-ws codes; clients reconnect after it. */
  static final CloseStatus CLIENT_FELL_BEHIND = new CloseStatus(4101, "Client fell behind");

  private final SendBufferSettings settings;
  private final Counter fellBehindClosures;
  private final Map<String, SendBufferedSession> sessions = new ConcurrentHashMap<>();

  SendBufferedWebSocketHandler(WebSocketHandler graphQlHandler, SendBufferSettings settings) {
    super(graphQlHandler);
    this.settings = settings;
    this.fellBehindClosures =
        Counter.builder("streamarr.graphql.websocket.client_fell_behind")
            .description("WebSocket connections closed because their client fell behind")
            .register(settings.meterRegistry());
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    waitForStalledWriteBeforeClosing(session);
    var buffered = new SendBufferedSession(session);
    sessions.put(session.getId(), buffered);
    super.afterConnectionEstablished(buffered);
  }

  private static void waitForStalledWriteBeforeClosing(WebSocketSession session) {
    if (!(WebSocketSessionDecorator.unwrap(session) instanceof NativeWebSocketSession adapter
        && adapter.getNativeSession() instanceof Session container)) {
      return;
    }

    // Tomcat sends a close frame only after the write in progress, and gives a close other than
    // 1000 just 50 ms to wait for it before it drops the connection without one. A client that
    // stopped reading stalls that write, so the close waits as long as Tomcat lets a write block.
    container
        .getUserProperties()
        .put(
            Constants.ABNORMAL_SESSION_CLOSE_SEND_TIMEOUT_PROPERTY,
            Constants.DEFAULT_BLOCKING_SEND_TIMEOUT);
  }

  @Override
  public void handleMessage(WebSocketSession session, WebSocketMessage<?> message)
      throws Exception {
    var buffered = sessions.get(session.getId());
    if (buffered == null || buffered.isClosing()) {
      return;
    }

    super.handleMessage(buffered, message);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    WebSocketSession target = sessions.get(session.getId());
    if (target == null) {
      target = session;
    }

    super.handleTransportError(target, exception);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
      throws Exception {
    var buffered = sessions.remove(session.getId());
    if (buffered == null) {
      return;
    }

    buffered.discardUnsent();
    super.afterConnectionClosed(buffered, closeStatus);
  }

  // The session the GraphQL handler sees. Its sendMessage queues the frame and returns at once.
  private final class SendBufferedSession extends WebSocketSessionDecorator {

    private final Deque<WebSocketMessage<?>> unsent = new ArrayDeque<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private long unsentBytes;
    private boolean writing;

    private SendBufferedSession(WebSocketSession session) {
      super(session);
    }

    private boolean isClosing() {
      return closing.get();
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) {
      if (isClosing()) {
        return;
      }

      if (!tryQueue(message)) {
        fellBehindClosures.increment();
        closeOnce(CLIENT_FELL_BEHIND);
        return;
      }

      startWriterUnlessWriting();
    }

    private synchronized boolean tryQueue(WebSocketMessage<?> message) {
      var size = message.getPayloadLength();
      if (unsentBytes + size > settings.capacity().toBytes()) {
        return false;
      }

      unsent.addLast(message);
      unsentBytes += size;
      return true;
    }

    private void startWriterUnlessWriting() {
      synchronized (this) {
        if (writing) {
          return;
        }

        writing = true;
      }

      settings.sendExecutor().execute(this::writeUnsent);
    }

    private void writeUnsent() {
      var next = nextUnsent();
      while (next != null && !isClosing()) {
        if (!tryWrite(next)) {
          return;
        }

        next = nextUnsent();
      }
    }

    // Returns null, and stops the writer, once nothing is left to write.
    private synchronized WebSocketMessage<?> nextUnsent() {
      var next = unsent.pollFirst();
      if (next == null) {
        writing = false;
        return null;
      }

      unsentBytes -= next.getPayloadLength();
      return next;
    }

    private boolean tryWrite(WebSocketMessage<?> message) {
      try {
        getDelegate().sendMessage(message);
        return true;
      } catch (IOException | IllegalStateException e) {
        log.debug("Could not write to a WebSocket session.", e);
        closeOnce(CloseStatus.SERVER_ERROR);
        return false;
      }
    }

    private synchronized void discardUnsent() {
      closing.set(true);
      unsent.clear();
      unsentBytes = 0;
    }

    // The close runs on the send executor: a container waits for a stalled write to finish
    // before it sends the close frame, and the GraphQL handler must not wait with it.
    private void closeOnce(CloseStatus status) {
      if (!closing.compareAndSet(false, true)) {
        return;
      }

      discardUnsent();
      settings.sendExecutor().execute(() -> closeQuietly(status));
    }

    private void closeQuietly(CloseStatus status) {
      try {
        getDelegate().close(status);
      } catch (IOException e) {
        log.debug("Could not close a WebSocket session cleanly.", e);
      }
    }
  }
}
