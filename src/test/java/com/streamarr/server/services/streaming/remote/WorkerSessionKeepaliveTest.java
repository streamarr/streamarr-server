package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Session Keepalive Tests")
class WorkerSessionKeepaliveTest {

  private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

  @Test
  @DisplayName("Should reject a keepalive when its time is missing")
  void shouldRejectKeepaliveWhenItsTimeIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> new WorkerSessionKeepalive(null, TEN_SECONDS))
        .withMessageContaining("time");
  }

  @Test
  @DisplayName("Should reject a keepalive when its timeout is missing")
  void shouldRejectKeepaliveWhenItsTimeoutIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> new WorkerSessionKeepalive(TEN_SECONDS, null))
        .withMessageContaining("timeout");
  }
}
