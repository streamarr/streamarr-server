package com.streamarr.server.graphql.transport;

import com.netflix.graphql.types.errors.ErrorType;
import com.streamarr.server.graphql.CodedGraphQlErrors;
import com.streamarr.server.graphql.subscriptions.SubscriptionProperties;
import graphql.ExecutionResult;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Caps the operations one WebSocket runs at once. An operation holds its place from subscribe until
 * it ends: the client completes it, it fails, or the connection closes. An operation over the cap
 * is refused with TOO_MANY_OPERATIONS before it executes.
 */
@Component
class WebSocketOperationLimitInterceptor implements WebSocketGraphQlInterceptor {

  private final int maxOperationsPerConnection;
  private final Map<String, Integer> activeOperationsBySession = new ConcurrentHashMap<>();

  WebSocketOperationLimitInterceptor(SubscriptionProperties properties) {
    this.maxOperationsPerConnection = properties.maxOperationsPerConnection();
  }

  @Override
  public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
    if (!(request instanceof WebSocketGraphQlRequest webSocketRequest)) {
      return chain.next(request);
    }

    var sessionId = webSocketRequest.getSessionInfo().getId();
    if (!tryReserve(sessionId)) {
      return Mono.just(refusal(request));
    }

    var reservation = new Reservation(sessionId);
    return chain
        .next(request)
        .map(response -> holdUntilOperationEnds(response, reservation))
        .doOnError(_ -> reservation.release())
        .doOnCancel(reservation::release);
  }

  @Override
  public void handleConnectionClosed(
      WebSocketSessionInfo sessionInfo, int statusCode, Map<String, Object> connectionInitPayload) {
    activeOperationsBySession.remove(sessionInfo.getId());
  }

  private boolean tryReserve(String sessionId) {
    var reserved = new AtomicBoolean();
    activeOperationsBySession.compute(
        sessionId,
        (_, active) -> {
          var current = 0;
          if (active != null) {
            current = active;
          }

          if (current >= maxOperationsPerConnection) {
            return active;
          }

          reserved.set(true);
          return current + 1;
        });
    return reserved.get();
  }

  private static WebGraphQlResponse holdUntilOperationEnds(
      WebGraphQlResponse response, Reservation reservation) {
    if (!(response.getData() instanceof Publisher<?> operation)) {
      reservation.release();
      return response;
    }

    return response.transform(
        builder -> builder.data(Flux.from(operation).doFinally(_ -> reservation.release())));
  }

  private WebGraphQlResponse refusal(WebGraphQlRequest request) {
    var error =
        CodedGraphQlErrors.withCode(
            ErrorType.BAD_REQUEST,
            "TOO_MANY_OPERATIONS",
            "A connection runs at most %d operations at once; complete one first."
                .formatted(maxOperationsPerConnection));
    return new WebGraphQlResponse(
        new DefaultExecutionGraphQlResponse(
            request.toExecutionInput(),
            ExecutionResult.newExecutionResult().addError(error).build()));
  }

  // One operation's place on its connection, given back exactly once.
  @RequiredArgsConstructor
  private final class Reservation {

    private final String sessionId;
    private final AtomicBoolean released = new AtomicBoolean();

    private void release() {
      if (!released.compareAndSet(false, true)) {
        return;
      }

      activeOperationsBySession.computeIfPresent(
          sessionId,
          (_, active) -> {
            if (active <= 1) {
              return null;
            }

            return active - 1;
          });
    }
  }
}
