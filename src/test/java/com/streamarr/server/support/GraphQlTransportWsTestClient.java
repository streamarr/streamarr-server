package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A raw {@code graphql-transport-ws} client on the JDK WebSocket, so tests observe every frame the
 * server sends: {@code next}, {@code error}, {@code complete} and the close frame. It answers
 * server pings itself and queues every other message by operation id, in arrival order.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class GraphQlTransportWsTestClient implements AutoCloseable {

  private static final String SUBPROTOCOL = "graphql-transport-ws";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration MESSAGE_BOUND = Duration.ofSeconds(10);

  private final HttpClient httpClient;
  private final WebSocket webSocket;
  private final Listener listener;

  /** The handshake request: the endpoint plus optional credentials and origin. */
  @Value
  @Builder
  public static class Handshake {
    @NonNull URI endpoint;
    @Builder.Default Optional<String> bearerToken = Optional.empty();
    @Builder.Default Optional<String> cookie = Optional.empty();
    @Builder.Default Optional<String> origin = Optional.empty();
  }

  /** A handshake to the server's {@code /graphql} WebSocket endpoint on the given port. */
  public static Handshake.HandshakeBuilder handshakeTo(int port) {
    return Handshake.builder().endpoint(URI.create("ws://localhost:%d/graphql".formatted(port)));
  }

  /** Opens a socket the way a native client does and waits for {@code connection_ack}. */
  public static GraphQlTransportWsTestClient connectWithBearer(int port, String bearer) {
    return connect(handshakeTo(port).bearerToken(Optional.of(bearer)).build()).initialize();
  }

  /** The close frame the server sent, or the local abort when the server never closed. */
  public record Closure(int statusCode, String reason) {}

  // A refused upgrade fails the future.
  private static CompletableFuture<GraphQlTransportWsTestClient> open(Handshake handshake) {
    var httpClient = HttpClient.newHttpClient();
    var listener = new Listener();
    var builder = httpClient.newWebSocketBuilder().subprotocols(SUBPROTOCOL);
    handshake
        .getBearerToken()
        .ifPresent(token -> builder.header("Authorization", "Bearer " + token));
    handshake.getCookie().ifPresent(cookie -> builder.header("Cookie", cookie));
    handshake.getOrigin().ifPresent(origin -> builder.header("Origin", origin));

    return builder
        .buildAsync(handshake.getEndpoint(), listener)
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

  /** The HTTP status of a handshake the server refused; fails if the upgrade succeeds. */
  public static int refusedHandshakeStatus(Handshake handshake) {
    try {
      var client = open(handshake).get(MESSAGE_BOUND.toMillis(), TimeUnit.MILLISECONDS);
      client.close();
      throw new AssertionError("The server accepted a handshake it should refuse");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof WebSocketHandshakeException refused) {
        return refused.getResponse().statusCode();
      }

      throw new AssertionError("The handshake failed without an HTTP response", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while opening the WebSocket", e);
    } catch (TimeoutException e) {
      throw new AssertionError("The handshake did not finish within " + MESSAGE_BOUND, e);
    }
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

  /** The next connection-level message, such as {@code connection_ack}, failing after a bound. */
  public JsonNode nextMessage() {
    return poll(listener.connectionMessages);
  }

  /** The next message for one operation, in arrival order, failing after a bound. */
  public JsonNode nextMessageFor(String operationId) {
    return poll(listener.operationMessages(operationId));
  }

  private static JsonNode poll(BlockingQueue<JsonNode> queue) {
    try {
      var message = queue.poll(MESSAGE_BOUND.toMillis(), TimeUnit.MILLISECONDS);
      assertThat(message).as("a message within %s", MESSAGE_BOUND).isNotNull();
      return message;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for a message", e);
    }
  }

  /** Stops reading frames, the way a client on a stalled network does. */
  public void pauseReading() {
    listener.pauseReading();
  }

  public void resumeReading() {
    listener.resumeReading(webSocket);
  }

  /** Every message received so far, including those already taken. */
  public List<JsonNode> receivedMessages() {
    return List.copyOf(listener.received);
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
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException _) {
      // The server already closed the socket; abort below releases it either way.
    } finally {
      webSocket.abort();
      httpClient.close();
    }
  }

  private static final class Listener implements WebSocket.Listener {

    private final BlockingQueue<JsonNode> connectionMessages = new LinkedBlockingQueue<>();
    private final Map<String, BlockingQueue<JsonNode>> operationMessages =
        new ConcurrentHashMap<>();
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Closure> closure = new CompletableFuture<>();
    private final StringBuilder partialText = new StringBuilder();
    private final Object demand = new Object();
    private boolean readingPaused;
    private boolean requestOwed;

    // While paused the client requests no more frames, so the server's writes stall once the
    // socket buffers between them fill.
    private void pauseReading() {
      synchronized (demand) {
        readingPaused = true;
      }
    }

    private void resumeReading(WebSocket webSocket) {
      synchronized (demand) {
        readingPaused = false;
        if (!requestOwed) {
          return;
        }

        requestOwed = false;
      }

      webSocket.request(1);
    }

    private void requestNext(WebSocket webSocket) {
      synchronized (demand) {
        if (readingPaused) {
          requestOwed = true;
          return;
        }
      }

      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partialText.append(data);
      if (last) {
        var message = JSON.readTree(partialText.toString());
        partialText.setLength(0);
        accept(webSocket, message);
      }

      requestNext(webSocket);
      return null;
    }

    private void accept(WebSocket webSocket, JsonNode message) {
      if ("ping".equals(message.path("type").asString())) {
        webSocket.sendText("{\"type\":\"pong\"}", true);
        return;
      }

      received.add(message);
      var operationId = message.path("id");
      if (operationId.isMissingNode() || operationId.isNull()) {
        connectionMessages.add(message);
        return;
      }

      operationMessages(operationId.asString()).add(message);
    }

    private BlockingQueue<JsonNode> operationMessages(String operationId) {
      return operationMessages.computeIfAbsent(operationId, _ -> new LinkedBlockingQueue<>());
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
