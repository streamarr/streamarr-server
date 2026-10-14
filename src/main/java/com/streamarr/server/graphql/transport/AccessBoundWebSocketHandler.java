package com.streamarr.server.graphql.transport;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

/**
 * Bounds a WebSocket connection by the access token that opened it (ADR 0016, ADR 0042). At expiry
 * the server closes the socket with {@link #ACCESS_EXPIRED} and sends nothing else first: no data
 * and no {@code complete}, so clients restore their operations on a new socket. A message that
 * arrives after expiry is refused the same way, and a frame due after expiry is never sent, even
 * when the expiry timer runs late.
 */
@Slf4j
class AccessBoundWebSocketHandler extends WebSocketHandlerDecorator {

  /** The session attribute that holds the opening access token's expiry, an {@link Instant}. */
  static final String ACCESS_EXPIRES_AT =
      AccessBoundWebSocketHandler.class.getName() + ".accessExpiresAt";

  /** A private close code outside the graphql-transport-ws codes; clients reconnect after it. */
  static final CloseStatus ACCESS_EXPIRED = new CloseStatus(4100, "Access token expired");

  private final Clock clock;
  private final TaskScheduler taskScheduler;
  private final Map<String, AccessBoundSession> sessions = new ConcurrentHashMap<>();

  AccessBoundWebSocketHandler(
      WebSocketHandler graphQlHandler, Clock clock, TaskScheduler taskScheduler) {
    super(graphQlHandler);
    this.clock = clock;
    this.taskScheduler = taskScheduler;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    var bound = new AccessBoundSession(session, accessExpiresAt(session), clock);
    // Started before the session is visible, so a close always finds the timer to cancel.
    bound.startExpiryTimer(taskScheduler);
    sessions.put(session.getId(), bound);
    super.afterConnectionEstablished(bound);
  }

  @Override
  public void handleMessage(WebSocketSession session, WebSocketMessage<?> message)
      throws Exception {
    var bound = sessions.get(session.getId());
    if (bound == null) {
      return;
    }

    if (bound.isExpired()) {
      bound.closeForExpiry();
      return;
    }

    super.handleMessage(bound, message);
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
    var bound = sessions.remove(session.getId());
    if (bound == null) {
      return;
    }

    bound.cancelExpiryTimer();
    super.afterConnectionClosed(bound, closeStatus);
  }

  // WebSocketAuthorityHandshakeInterceptor accepts no handshake without recording the expiry.
  private static Instant accessExpiresAt(WebSocketSession session) {
    if (session.getAttributes().get(ACCESS_EXPIRES_AT) instanceof Instant expiresAt) {
      return expiresAt;
    }

    throw new IllegalStateException("The WebSocket handshake recorded no access-token expiry.");
  }

  // The one session object the GraphQL handler sees; it refuses to send after expiry.
  private static final class AccessBoundSession extends WebSocketSessionDecorator {

    private final Instant accessExpiresAt;
    private final Clock clock;
    private volatile ScheduledFuture<?> expiryTimer;

    private AccessBoundSession(WebSocketSession session, Instant accessExpiresAt, Clock clock) {
      super(session);
      this.accessExpiresAt = accessExpiresAt;
      this.clock = clock;
    }

    private boolean isExpired() {
      return !clock.instant().isBefore(accessExpiresAt);
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
      if (isExpired()) {
        closeForExpiry();
        return;
      }

      super.sendMessage(message);
    }

    private void startExpiryTimer(TaskScheduler taskScheduler) {
      expiryTimer = taskScheduler.schedule(this::closeForExpiry, accessExpiresAt);
    }

    // The container ignores a close after the first, so a late send and the timer may both close.
    private void closeForExpiry() {
      try {
        getDelegate().close(ACCESS_EXPIRED);
      } catch (IOException e) {
        log.debug("Could not close an expired WebSocket session cleanly.", e);
      }
    }

    private void cancelExpiryTimer() {
      expiryTimer.cancel(false);
    }
  }
}
