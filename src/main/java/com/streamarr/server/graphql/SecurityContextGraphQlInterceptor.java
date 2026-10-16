package com.streamarr.server.graphql;

import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.security.core.context.SecurityContextHolderThreadLocalAccessor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Keeps the security context that opened each operation in its GraphQL context, so every data
 * fetcher resolves as that identity on whichever thread runs it, including each event of a
 * subscription.
 */
@Component
public class SecurityContextGraphQlInterceptor implements WebGraphQlInterceptor {

  // Spring for GraphQL restores GraphQL context entries keyed by a ThreadLocalAccessor around every
  // data fetcher. Its own copy of the request thread's context travels in the Reactor context,
  // which the DGS interceptor drops when it blocks on the rest of the chain. Only the security
  // context is carried: other thread-local state, such as the handshake's observation, ends with
  // the request that opened the operation.
  private static final ContextSnapshotFactory SECURITY_CONTEXT_SNAPSHOTS =
      ContextSnapshotFactory.builder()
          .captureKeyPredicate(new SecurityContextHolderThreadLocalAccessor().key()::equals)
          .build();

  @Override
  public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
    var opening = SECURITY_CONTEXT_SNAPSHOTS.captureAll();
    request.configureExecutionInput(
        (input, builder) -> {
          opening.updateContext(input.getGraphQLContext());
          return input;
        });
    return chain.next(request);
  }
}
