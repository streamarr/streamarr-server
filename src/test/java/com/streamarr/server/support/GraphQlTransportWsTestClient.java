package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import lombok.Builder;
import lombok.NonNull;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A raw {@code graphql-transport-ws} client on the JDK WebSocket, so tests observe every frame the
 * server sends: {@code next}, {@code error}, {@code complete} and the close frame. It answers
 * server pings itself and keeps every other message in arrival order.
 */
public final class GraphQlTransportWsTestClient implements AutoCloseable {

  public static final String SUBPROTOCOL = "graphql-transport-ws";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration MESSAGE_BOUND = Duration.ofSeconds(10);

  private final HttpClient httpClient;
  private final WebSocket webSocket;
  private final Listener listener;

  private GraphQlTransportWsTestClient(
      HttpClient httpClient, WebSocket webSocket, Listener listener) {
    this.httpClient = httpClient;
    this.webSocket = webSocket;
    this.listener = listener;
  }

  /** The handshake request: the endpoint plus optional credentials and origin. */
  @Builder
  public record Handshake(
      @NonNull URI endpoint,
      Optional<String> bearerToken,
      Optional<String> cookie,
      Optional<String> origin) {

    public Handshake {
      bearerToken = bearerToken == null ? Optional.empty() : bearerToken;
      cookie = cookie == null ? Optional.empty() : cookie;
      origin = origin == null ? Optional.empty() : origin;
    }
  }

  /** The close frame the server sent, or the local abort when the server never closed. */
  public record Closure(int statusCode, String reason) {}

  /** Opens the socket and completes the handshake; a refused upgrade fails the future. */
  public static CompletableFuture<GraphQlTransportWsTestClient> open(Handshake handshake) {
    var httpClient = HttpClient.newHttpClient();
    var listener = new Listener();
    var builder = httpClient.newWebSocketBuilder().subprotocols(SUBPROTOCOL);
    handshake.bearerToken().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
    handshake.cookie().ifPresent(cookie -> builder.header("Cookie", cookie));
    handshake.origin().ifPresent(origin -> builder.header("Origin", origin));

    return builder
        .buildAsync(handshake.endpoint(), listener)
        .thenApply(webSocket -> new GraphQlTransportWsTestClient(httpClient, webSocket, listener))
        .whenComplete(
            (client, failure) -> {
              if (failure != null) {
                httpClient.close();
              }
            });
  }

  public static GraphQlTransportWsTestClient connect(Handshake handshake) {
    return bounded(open(handshake));
  }

  /** Sends {@code connection_init} and waits for {@code connection_ack}. */
  public GraphQlTransportWsTestClient initialize() {
    send(Map.of("type", "connection_init"));
    var ack = nextMessage();
    assertThat(ack.path("type").asString()).isEqualTo("connection_ack");
    return this;
  }

  public void subscribe(String operationId, String document) {
    send(Map.of("type", "subscribe", "id", operationId, "payload", Map.of("query", document)));
  }

  public void complete(String operationId) {
    send(Map.of("type", "complete", "id", operationId));
  }

  /** The next message the server sent, failing after a bound. */
  public JsonNode nextMessage() {
    try {
      var message = listener.messages.poll(MESSAGE_BOUND.toMillis(), TimeUnit.MILLISECONDS);
      assertThat(message).as("a message within %s", MESSAGE_BOUND).isNotNull();
      return message;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for a message", e);
    }
  }

  /** The next message for one operation, skipping other operations' messages. */
  public JsonNode nextMessageFor(String operationId) {
    return nextMessageMatching(message -> operationId.equals(message.path("id").asString()));
  }

  /** The next message that satisfies the predicate, skipping the others. */
  public JsonNode nextMessageMatching(Predicate<JsonNode> predicate) {
    while (true) {
      var message = nextMessage();
      if (predicate.test(message)) {
        return message;
      }
    }
  }

  /** Every message received so far, including those already taken. */
  public List<JsonNode> receivedMessages() {
    return List.copyOf(listener.received);
  }

  /** Completes when the server closes the socket. */
  public CompletableFuture<Closure> closure() {
    return listener.closure;
  }

  public Closure awaitClosure() {
    return bounded(listener.closure);
  }

  private void send(Map<String, Object> message) {
    bounded(webSocket.sendText(JSON.writeValueAsString(message), true));
  }

  private static <T> T bounded(CompletableFuture<T> future) {
    try {
      return future.get(MESSAGE_BOUND.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting on the WebSocket", e);
    } catch (ExecutionException e) {
      throw new AssertionError("The WebSocket operation failed", e.getCause());
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new AssertionError("The WebSocket operation did not finish within " + MESSAGE_BOUND, e);
    }
  }

  @Override
  public void close() {
    try {
      if (!webSocket.isOutputClosed()) {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(1, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException _) {
      // The server already closed the socket; abort below releases it either way.
    } finally {
      webSocket.abort();
      httpClient.close();
    }
  }

  private static final class Listener implements WebSocket.Listener {

    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Closure> closure = new CompletableFuture<>();
    private final StringBuilder partialText = new StringBuilder();

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partialText.append(data);
      if (last) {
        var message = JSON.readTree(partialText.toString());
        partialText.setLength(0);
        accept(webSocket, message);
      }

      webSocket.request(1);
      return null;
    }

    private void accept(WebSocket webSocket, JsonNode message) {
      if ("ping".equals(message.path("type").asString())) {
        webSocket.sendText("{\"type\":\"pong\"}", true);
        return;
      }

      received.add(message);
      messages.add(message);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closure.complete(new Closure(statusCode, reason));
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      closure.completeExceptionally(error);
    }
  }
}
