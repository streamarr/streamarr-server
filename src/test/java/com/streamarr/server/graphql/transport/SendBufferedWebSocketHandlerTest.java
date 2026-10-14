package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fakes.EchoingWebSocketHandler;
import com.streamarr.server.fakes.FakeWebSocketSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

@Tag("UnitTest")
@DisplayName("Send-Buffered WebSocket Handler Tests")
class SendBufferedWebSocketHandlerTest {

  private static final DataSize CAPACITY = DataSize.ofBytes(10);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ExecutorService sendExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final EchoingWebSocketHandler graphQlHandler = new EchoingWebSocketHandler();
  private final SendBufferedWebSocketHandler handler =
      new SendBufferedWebSocketHandler(
          graphQlHandler,
          SendBufferSettings.builder()
              .sendExecutor(sendExecutor)
              .capacity(CAPACITY)
              .meterRegistry(meterRegistry)
              .build());
  private final FakeWebSocketSession session = new FakeWebSocketSession(Map.of());

  @AfterEach
  void releaseWrites() {
    session.releaseWrites();
    sendExecutor.close();
  }

  @Test
  @DisplayName("Should write every frame in send order when the client keeps up")
  void shouldWriteEveryFrameInSendOrderWhenClientKeepsUp() throws Exception {
    handler.afterConnectionEstablished(session);

    graphQlHandler.deliver("a");
    graphQlHandler.deliver("b");
    graphQlHandler.deliver("c");
    sendExecutor.close();

    assertThat(writtenPayloads()).containsExactly("a", "b", "c");
    assertThat(session.closeStatus()).isEmpty();
  }

  @Test
  @DisplayName("Should close with 4101 when unsent frames would exceed the buffer")
  void shouldCloseWith4101WhenUnsentFramesWouldExceedBuffer() throws Exception {
    handler.afterConnectionEstablished(session);
    stallWritesAfter("first");

    graphQlHandler.deliver("0123456789");
    graphQlHandler.deliver("x");

    assertThat(session.awaitCloseStatus())
        .isEqualTo(SendBufferedWebSocketHandler.CLIENT_FELL_BEHIND);
    assertThat(session.awaitCloseStatus().getCode()).isEqualTo(4101);
    assertThat(session.awaitCloseStatus().getReason()).isEqualTo("Client fell behind");
    assertThat(fellBehindClosures()).isEqualTo(1);
    session.releaseWrites();
    sendExecutor.close();
    assertThat(writtenPayloads()).containsExactly("first");
  }

  @Test
  @DisplayName("Should keep the socket open when unsent frames exactly fill the buffer")
  void shouldKeepSocketOpenWhenUnsentFramesExactlyFillBuffer() throws Exception {
    handler.afterConnectionEstablished(session);
    stallWritesAfter("first");

    graphQlHandler.deliver("0123456789");
    session.releaseWrites();
    sendExecutor.close();

    assertThat(writtenPayloads()).containsExactly("first", "0123456789");
    assertThat(session.closeStatus()).isEmpty();
    assertThat(fellBehindClosures()).isZero();
  }

  @Test
  @DisplayName("Should drop an incoming message when the client fell behind")
  void shouldDropIncomingMessageWhenClientFellBehind() throws Exception {
    handler.afterConnectionEstablished(session);
    stallWritesAfter("first");
    graphQlHandler.deliver("0123456789");
    graphQlHandler.deliver("x");
    session.awaitCloseStatus();

    handler.handleMessage(session, new TextMessage("subscribe"));

    assertThat(graphQlHandler.receivedMessages()).isEmpty();
  }

  @Test
  @DisplayName("Should hand an incoming message to the GraphQL handler when the client keeps up")
  void shouldHandIncomingMessageToGraphQlHandlerWhenClientKeepsUp() throws Exception {
    handler.afterConnectionEstablished(session);

    handler.handleMessage(session, new TextMessage("s"));
    sendExecutor.close();

    assertThat(graphQlHandler.receivedMessages()).hasSize(1);
    assertThat(writtenPayloads()).containsExactly("echo:s");
  }

  @Test
  @DisplayName("Should discard unsent frames when the connection closes")
  void shouldDiscardUnsentFramesWhenConnectionCloses() throws Exception {
    handler.afterConnectionEstablished(session);
    stallWritesAfter("first");
    graphQlHandler.deliver("queued");

    handler.afterConnectionClosed(session, CloseStatus.GOING_AWAY);
    session.releaseWrites();
    sendExecutor.close();

    assertThat(writtenPayloads()).containsExactly("first");
    assertThat(graphQlHandler.closedSessions()).hasSize(1);
  }

  @Test
  @DisplayName("Should close as a server error when a write fails")
  void shouldCloseAsServerErrorWhenWriteFails() throws Exception {
    handler.afterConnectionEstablished(session);
    session.failWrites();

    graphQlHandler.deliver("first");

    assertThat(session.awaitCloseStatus()).isEqualTo(CloseStatus.SERVER_ERROR);
    assertThat(fellBehindClosures()).isZero();
  }

  @Test
  @DisplayName("Should keep dropping frames when closing a client that fell behind fails")
  void shouldKeepDroppingFramesWhenClosingClientThatFellBehindFails() throws Exception {
    handler.afterConnectionEstablished(session);
    session.failCloses();
    stallWritesAfter("first");
    graphQlHandler.deliver("0123456789");

    graphQlHandler.deliver("x");
    graphQlHandler.deliver("y");
    session.releaseWrites();
    sendExecutor.close();

    assertThat(writtenPayloads()).containsExactly("first");
    assertThat(fellBehindClosures()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should ignore a message when its session was never established")
  void shouldIgnoreMessageWhenItsSessionWasNeverEstablished() throws Exception {
    handler.handleMessage(session, new TextMessage("subscribe"));

    assertThat(graphQlHandler.receivedMessages()).isEmpty();
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
  @DisplayName("Should not report a closure when the session was never established")
  void shouldNotReportClosureWhenSessionWasNeverEstablished() throws Exception {
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    assertThat(graphQlHandler.closedSessions()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sendExecutor", "capacity", "meterRegistry"})
  @DisplayName("Should refuse to build the settings when a required argument is missing")
  void shouldRefuseToBuildSettingsWhenRequiredArgumentIsMissing(String argument) {
    var builder = SendBufferSettings.builder();
    requiredArguments().entrySet().stream()
        .filter(required -> !required.getKey().equals(argument))
        .forEach(required -> required.getValue().accept(builder));

    assertThatThrownBy(builder::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageStartingWith(argument + " ");
  }

  // The first write starts and stalls, the way a write to a client that stopped reading does.
  private void stallWritesAfter(String payload) throws Exception {
    session.blockWrites();
    graphQlHandler.deliver(payload);
    session.awaitBlockedWrite();
  }

  private Map<String, Consumer<SendBufferSettings.SendBufferSettingsBuilder>> requiredArguments() {
    return Map.of(
        "sendExecutor",
        builder -> builder.sendExecutor(sendExecutor),
        "capacity",
        builder -> builder.capacity(CAPACITY),
        "meterRegistry",
        builder -> builder.meterRegistry(meterRegistry));
  }

  private double fellBehindClosures() {
    return meterRegistry.get("streamarr.graphql.websocket.client_fell_behind").counter().count();
  }

  private List<String> writtenPayloads() {
    return session.sentMessages().stream()
        .map(WebSocketMessage::getPayload)
        .map(String::valueOf)
        .toList();
  }
}
