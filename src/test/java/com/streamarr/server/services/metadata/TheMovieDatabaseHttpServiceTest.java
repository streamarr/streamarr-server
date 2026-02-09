package com.streamarr.server.services.metadata;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("TMDB HTTP Service Tests")
class TheMovieDatabaseHttpServiceTest {

  @ParameterizedTest
  @ValueSource(ints = {0, -1, -100})
  @DisplayName("Should reject non-positive max concurrent requests")
  void shouldRejectNonPositiveMaxConcurrentRequests(int maxConcurrentRequests) {
    assertThatThrownBy(
            () -> new TheMovieDatabaseHttpService("", "", "", maxConcurrentRequests, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max-concurrent-requests");
  }

  @Test
  @DisplayName("Should accept valid max concurrent requests")
  void shouldAcceptValidMaxConcurrentRequests() {
    var service = new TheMovieDatabaseHttpService("", "", "", 1, null, null);

    // Construction succeeds without throwing
    assert service != null;
  }
}
