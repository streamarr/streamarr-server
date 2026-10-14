package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fakes.FakeTaskScheduler;
import com.streamarr.server.fakes.FakeWebSocketSession;
import com.streamarr.server.fakes.MutableClock;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Tag("UnitTest")
@DisplayName("Access-Bound WebSocket Handler Tests")
class AccessBoundWebSocketHandlerTest {

  private static final Duration ACCESS_LIFETIME = Duration.ofMinutes(10);

  private final MutableClock clock = new MutableClock();
  private final FakeTaskScheduler taskScheduler = new FakeTaskScheduler();
  private final EchoingWebSocketHandler graphQlHandler = new EchoingWebSocketHandler();
  private final AccessBoundWebSocketHandler handler =
      new AccessBoundWebSocketHandler(graphQlHandler, clock, taskScheduler);
  private final FakeWebSocketSession session =
      new FakeWebSocketSession(
          Map.of(
              AccessBoundWebSocketHandler.ACCESS_EXPIRES_AT,
              clock.instant().plus(ACCESS_LIFETIME)));

  @Test
  @DisplayName("Should pass messages through when the access token is still valid")
  void shouldPassMessagesThroughWhenAccessTokenIsStillValid() throws Exception {
    handler.afterConnectionEstablished(session);

    handler.handleMessage(session, new TextMessage("subscribe"));

    assertThat(sentPayloads()).containsExactly("echo:subscribe");
    assertThat(session.closeStatus()).isEmpty();
  }

  @Test
  @DisplayName("Should close with the access-expired code instead of sending when the clock passes")
  void shouldCloseWithAccessExpiredCodeInsteadOfSendingWhenClockPassesExpiry() throws Exception {
    handler.afterConnectionEstablished(session);
    clock.advance(ACCESS_LIFETIME);

    graphQlHandler.deliver("next");

    assertThat(session.sentMessages()).isEmpty();
    assertThat(session.closeStatus()).contains(AccessBoundWebSocketHandler.ACCESS_EXPIRED);
  }

  @Test
  @DisplayName("Should close without handing over a message that arrives after expiry")
  void shouldCloseWithoutHandingOverMessageThatArrivesAfterExpiry() throws Exception {
    handler.afterConnectionEstablished(session);
    clock.advance(ACCESS_LIFETIME.plusSeconds(1));

    handler.handleMessage(session, new TextMessage("subscribe"));

    assertThat(graphQlHandler.receivedMessages()).isEmpty();
    assertThat(session.closeStatus()).contains(AccessBoundWebSocketHandler.ACCESS_EXPIRED);
  }

  @Test
  @DisplayName("Should close with the access-expired code and reason when the expiry timer fires")
  void shouldCloseWithAccessExpiredCodeAndReasonWhenExpiryTimerFires() throws Exception {
    handler.afterConnectionEstablished(session);
    clock.advance(ACCESS_LIFETIME);

    taskScheduler.runTasksDueAt(clock.instant());

    assertThat(session.closeStatus())
        .hasValueSatisfying(
            status -> {
              assertThat(status.getCode()).isEqualTo(4100);
              assertThat(status.getReason()).isEqualTo("Access token expired");
            });
  }

  @Test
  @DisplayName("Should not close before the access token expires")
  void shouldNotCloseBeforeAccessTokenExpires() throws Exception {
    handler.afterConnectionEstablished(session);
    clock.advance(ACCESS_LIFETIME.minusSeconds(1));

    taskScheduler.runTasksDueAt(clock.instant());

    assertThat(session.closeStatus()).isEmpty();
  }

  @Test
  @DisplayName("Should cancel the expiry timer when the connection closes")
  void shouldCancelExpiryTimerWhenConnectionCloses() throws Exception {
    handler.afterConnectionEstablished(session);

    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    assertThat(taskScheduler.pendingTaskCount()).isZero();
  }

  @Test
  @DisplayName("Should fail the connection when the handshake recorded no access expiry")
  void shouldFailConnectionWhenHandshakeRecordedNoAccessExpiry() {
    var unbounded = new FakeWebSocketSession(Map.of());

    assertThatThrownBy(() -> handler.afterConnectionEstablished(unbounded))
        .isInstanceOf(IllegalStateException.class);
    assertThat(graphQlHandler.establishedSession()).isNull();
    assertThat(taskScheduler.pendingTaskCount()).isZero();
  }

  @Test
  @DisplayName("Should ignore a message when its session was never established")
  void shouldIgnoreMessageWhenItsSessionWasNeverEstablished() throws Exception {
    handler.handleMessage(session, new TextMessage("subscribe"));

    assertThat(graphQlHandler.receivedMessages()).isEmpty();
    assertThat(session.sentMessages()).isEmpty();
  }

  @Test
  @DisplayName("Should report a transport error against the session the GraphQL handler knows")
  void shouldReportTransportErrorAgainstSessionGraphQlHandlerKnows() throws Exception {
    handler.afterConnectionEstablished(session);

    handler.handleTransportError(session, new IOException("reset"));

    assertThat(graphQlHandler.transportErrorSession())
        .isSameAs(graphQlHandler.establishedSession());
  }

  @Test
  @DisplayName("Should report a transport error against the raw session when none was established")
  void shouldReportTransportErrorAgainstRawSessionWhenNoneWasEstablished() throws Exception {
    handler.handleTransportError(session, new IOException("reset"));

    assertThat(graphQlHandler.transportErrorSession()).isSameAs(session);
  }

  @Test
  @DisplayName(
      "Should not report a closure to the GraphQL handler when the session was never established")
  void shouldNotReportClosureWhenSessionWasNeverEstablished() throws Exception {
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    assertThat(graphQlHandler.closedSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should close only once when a late send and the expiry timer both see expiry")
  void shouldCloseOnlyOnceWhenLateSendAndExpiryTimerBothSeeExpiry() throws Exception {
    handler.afterConnectionEstablished(session);
    clock.advance(ACCESS_LIFETIME);
    graphQlHandler.deliver("next");

    taskScheduler.runTasksDueAt(clock.instant());

    assertThat(session.closeRequests()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should drop the frame without failing when closing an expired session fails")
  void shouldDropFrameWithoutFailingWhenClosingExpiredSessionFails() throws Exception {
    handler.afterConnectionEstablished(session);
    clock.advance(ACCESS_LIFETIME);
    session.failCloses();

    graphQlHandler.deliver("next");

    assertThat(session.sentMessages()).isEmpty();
    assertThat(session.closeRequests()).isEqualTo(1);
  }

  private List<String> sentPayloads() {
    return session.sentMessages().stream()
        .map(WebSocketMessage::getPayload)
        .map(String::valueOf)
        .toList();
  }

  // Stands in for the GraphQL handler: it echoes requests and can deliver unprompted frames.
  private static final class EchoingWebSocketHandler extends TextWebSocketHandler {

    private final AtomicReference<WebSocketSession> established = new AtomicReference<>();
    private final AtomicReference<WebSocketSession> transportErrorSession = new AtomicReference<>();
    private final List<TextMessage> receivedMessages = new CopyOnWriteArrayList<>();
    private final List<WebSocketSession> closedSessions = new CopyOnWriteArrayList<>();

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
      transportErrorSession.set(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
      closedSessions.add(session);
    }

    private WebSocketSession transportErrorSession() {
      return transportErrorSession.get();
    }

    private List<WebSocketSession> closedSessions() {
      return List.copyOf(closedSessions);
    }

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

    private void deliver(String payload) throws IOException {
      established.get().sendMessage(new TextMessage(payload));
    }

    private WebSocketSession establishedSession() {
      return established.get();
    }

    private List<TextMessage> receivedMessages() {
      return List.copyOf(receivedMessages);
    }
  }
}
