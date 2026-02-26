package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class StderrDrainerTest {

  @Test
  @DisplayName("Should capture lines written to the input stream")
  void shouldCaptureLinesWrittenToTheInputStream() throws Exception {
    var output = new PipedOutputStream();
    var input = new PipedInputStream(output);

    try (var drainer = new StderrDrainer(input)) {
      output.write("line1\nline2\nline3\n".getBytes(StandardCharsets.UTF_8));
      output.flush();

      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(drainer.getRecentOutput()).hasSize(3));

      assertThat(drainer.getRecentOutput()).containsExactly("line1", "line2", "line3");
    }
  }

  @Test
  @DisplayName("Should return empty list when stream is empty")
  void shouldReturnEmptyListWhenStreamIsEmpty() throws Exception {
    var input = new ByteArrayInputStream(new byte[0]);

    try (var drainer = new StderrDrainer(input)) {
      await().pollDelay(Duration.ofMillis(100)).until(() -> true);

      assertThat(drainer.getRecentOutput()).isEmpty();
    }
  }

  @Test
  @DisplayName("Should terminate drain thread when stream closes")
  void shouldTerminateDrainThreadWhenStreamCloses() throws Exception {
    var output = new PipedOutputStream();
    var input = new PipedInputStream(output);

    var drainer = new StderrDrainer(input);
    output.write("hello\n".getBytes(StandardCharsets.UTF_8));
    output.flush();
    output.close();

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(drainer.isDrainThreadAlive()).isFalse());

    drainer.close();
  }

  @Test
  @DisplayName("Should allow idempotent close")
  void shouldAllowIdempotentClose() throws Exception {
    var input = new ByteArrayInputStream("line\n".getBytes(StandardCharsets.UTF_8));
    var drainer = new StderrDrainer(input);

    assertThatNoException()
        .isThrownBy(
            () -> {
              drainer.close();
              drainer.close();
              drainer.close();
            });
  }

  @Test
  @DisplayName("Should retain only recent lines when output exceeds buffer capacity")
  void shouldRetainOnlyRecentLinesWhenOutputExceedsBufferCapacity() throws Exception {
    var output = new PipedOutputStream();
    var input = new PipedInputStream(output);

    try (var drainer = new StderrDrainer(input, 3)) {
      output.write("a\nb\nc\nd\ne\n".getBytes(StandardCharsets.UTF_8));
      output.flush();

      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(drainer.getRecentOutput()).hasSize(3));

      assertThat(drainer.getRecentOutput()).containsExactly("c", "d", "e");
    }
  }
}
