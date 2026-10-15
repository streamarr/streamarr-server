package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Subscription Overflow Exception Resolver Tests")
class SubscriptionOverflowExceptionResolverTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  private final SubscriptionOverflowExceptionResolver resolver =
      new SubscriptionOverflowExceptionResolver();

  @Test
  @DisplayName("Should resolve an overflow to a recoverable SUBSCRIPTION_OVERFLOW error")
  void shouldResolveOverflowToRecoverableSubscriptionOverflowError() {
    var errors =
        resolver
            .resolveException(new SubscriptionOverflowException("libraryStatusChanged"))
            .block(BOUND);

    assertThat(errors)
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getExtensions())
                  .containsEntry("errorType", "UNAVAILABLE")
                  .containsEntry("code", "SUBSCRIPTION_OVERFLOW");
              assertThat(error.getMessage()).contains("libraryStatusChanged");
            });
  }

  @Test
  @DisplayName("Should leave any other failure to the sanitized default error")
  void shouldLeaveAnyOtherFailureToSanitizedDefaultError() {
    var errors =
        resolver.resolveException(new IllegalStateException("internal detail")).block(BOUND);

    assertThat(errors).isNull();
  }
}
