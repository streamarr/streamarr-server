package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

@Tag("UnitTest")
@DisplayName("TMDB Health Indicator Tests")
class TmdbHealthIndicatorTest {

  @SuppressWarnings("unchecked")
  private final HttpResponse<Void> response = mock(HttpResponse.class);

  private final HttpClient httpClient = mock(HttpClient.class);
  private final TmdbHealthIndicator indicator = new TmdbHealthIndicator(httpClient);

  @Test
  @DisplayName("Should report UP when TMDB returns 200")
  void shouldReportUpWhenTmdbReturns200() throws Exception {
    doReturn(response).when(httpClient).send(any(), any());
    when(response.statusCode()).thenReturn(200);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("Should report UP when TMDB returns 401 (reachable but unauthorized)")
  void shouldReportUpWhenTmdbReturns401() throws Exception {
    doReturn(response).when(httpClient).send(any(), any());
    when(response.statusCode()).thenReturn(401);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("Should report DOWN when TMDB returns non-200/401 status")
  void shouldReportDownWhenTmdbReturnsUnexpectedStatus() throws Exception {
    doReturn(response).when(httpClient).send(any(), any());
    when(response.statusCode()).thenReturn(503);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  @DisplayName("Should report DOWN when IOException is thrown")
  void shouldReportDownWhenIOExceptionThrown() throws Exception {
    doThrow(new IOException("connection refused")).when(httpClient).send(any(), any());

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  @DisplayName("Should re-interrupt thread when InterruptedException is thrown")
  void shouldReInterruptThreadWhenInterruptedExceptionThrown() throws Exception {
    doThrow(new InterruptedException("interrupted")).when(httpClient).send(any(), any());

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();

    // Clear the interrupt flag to avoid polluting other tests
    Thread.interrupted();
  }
}
