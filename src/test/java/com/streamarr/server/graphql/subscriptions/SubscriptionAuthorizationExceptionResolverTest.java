package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Subscription Authorization Exception Resolver Tests")
class SubscriptionAuthorizationExceptionResolverTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  private final SubscriptionAuthorizationExceptionResolver resolver =
      new SubscriptionAuthorizationExceptionResolver();

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorizationFailures")
  @DisplayName(
      "Should resolve an authorization failure to its query error code when it ends an operation")
  void shouldResolveAnAuthorizationFailureToItsQueryErrorCodeWhenItEndsAnOperation(
      Throwable failure, String errorType, String code) {
    var errors = resolver.resolveException(failure).block(BOUND);

    assertThat(errors)
        .singleElement()
        .satisfies(
            error ->
                assertThat(error.getExtensions())
                    .containsEntry("errorType", errorType)
                    .containsEntry("code", code));
  }

  static Stream<Arguments> authorizationFailures() {
    return Stream.of(
        Arguments.of(
            Named.of("a denial", new AccessDeniedException("denied")),
            "PERMISSION_DENIED",
            "FORBIDDEN"),
        Arguments.of(
            Named.of("an engine that could not decide", new AuthorizationUnavailableException()),
            "UNAVAILABLE",
            "AUTHORIZATION_UNAVAILABLE"));
  }

  @Test
  @DisplayName("Should leave any other failure to the other resolvers when it ends an operation")
  void shouldLeaveAnyOtherFailureToTheOtherResolversWhenItEndsAnOperation() {
    var errors =
        resolver.resolveException(new IllegalStateException("internal detail")).block(BOUND);

    assertThat(errors).isNull();
  }
}
