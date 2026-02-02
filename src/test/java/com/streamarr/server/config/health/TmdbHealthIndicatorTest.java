package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

@Tag("UnitTest")
@DisplayName("TMDB Health Indicator Tests")
class TmdbHealthIndicatorTest {

  @SuppressWarnings("unchecked")
  private final HttpClient httpClient = mock(HttpClient.class);

  private final TmdbHealthIndicator indicator = new TmdbHealthIndicator(httpClient);

  @Test
  @DisplayName("Should re-interrupt thread when InterruptedException is thrown")
  void shouldReInterruptThreadWhenInterruptedExceptionThrown() throws Exception {
    when(httpClient.send(any(), any())).thenThrow(new InterruptedException("interrupted"));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();

    // Clear the interrupt flag to avoid polluting other tests
    Thread.interrupted();
  }
}
