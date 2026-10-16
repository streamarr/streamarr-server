package com.streamarr.server.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import graphql.GraphQLContext;
import io.micrometer.context.ContextSnapshotFactory;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

@Tag("UnitTest")
@DisplayName("Security Context GraphQL Interceptor Tests")
class SecurityContextGraphQlInterceptorTest {

  private final SecurityContextGraphQlInterceptor interceptor =
      new SecurityContextGraphQlInterceptor();
  private final SecurityContextAuthorizationService authorizationService =
      new SecurityContextAuthorizationService(
          new FakeAuthorizationDecider(), new FakeUserAccountRepository());

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName(
      "Should resolve the opening identity when a data fetcher runs on a thread without a security context")
  void shouldResolveOpeningIdentityWhenDataFetcherRunsOnThreadWithoutSecurityContext()
      throws Exception {
    var opener = identity();
    var context = interceptedAs(opener);

    assertThat(currentIdentityOnAnotherThread(context)).isEqualTo(opener);
  }

  @Test
  @DisplayName("Should resolve each operation's own identity when two identities open operations")
  void shouldResolveEachOperationsOwnIdentityWhenTwoIdentitiesOpenOperations() throws Exception {
    var first = identity();
    var second = identity();
    var firstContext = interceptedAs(first);
    var secondContext = interceptedAs(second);

    assertThat(currentIdentityOnAnotherThread(firstContext)).isEqualTo(first);
    assertThat(currentIdentityOnAnotherThread(secondContext)).isEqualTo(second);
  }

  private static AuthenticatedIdentity identity() {
    return AuthenticatedIdentityFixture.profileScopedBuilder().accountId(UUID.randomUUID()).build();
  }

  private GraphQLContext interceptedAs(AuthenticatedIdentity identity) {
    SecurityContextHolder.setContext(
        new SecurityContextImpl(new StreamarrAuthenticationToken(identity, jwt(), List.of())));
    var request =
        new WebGraphQlRequest(
            URI.create("/graphql"),
            new HttpHeaders(),
            null,
            null,
            Map.of(),
            Map.of("query", "{ __typename }"),
            UUID.randomUUID().toString(),
            null);

    interceptor.intercept(request, _ -> Mono.empty()).block();
    SecurityContextHolder.clearContext();
    return request.toExecutionInput().getGraphQLContext();
  }

  // Restores the operation's context the way Spring for GraphQL does around every data fetcher.
  private AuthenticatedIdentity currentIdentityOnAnotherThread(GraphQLContext context)
      throws Exception {
    var snapshot = ContextSnapshotFactory.builder().build().captureFrom(context);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(snapshot.wrap(authorizationService::currentIdentity)).get();
    }
  }

  private static Jwt jwt() {
    return Jwt.withTokenValue("[redacted]").header("alg", "none").claim("sub", "opener").build();
  }
}
