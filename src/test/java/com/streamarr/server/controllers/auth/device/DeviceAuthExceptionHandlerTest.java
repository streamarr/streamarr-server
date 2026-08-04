package com.streamarr.server.controllers.auth.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;

@Tag("UnitTest")
@DisplayName("Device Auth Exception Handler Tests")
class DeviceAuthExceptionHandlerTest {

  private final DeviceAuthExceptionHandler handler = new DeviceAuthExceptionHandler();

  @ParameterizedTest(name = "Should render {1} seconds for wait {0}")
  @MethodSource("retryAfterExamples")
  @DisplayName("Should round retry-after up to a positive integer second")
  void shouldRoundRetryAfterUpToPositiveIntegerSecond(Duration wait, String expectedSeconds) {
    var response = handler.handleTooManyAttempts(new TooManyDeviceAttemptsException(wait));

    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo(expectedSeconds);
  }

  private static Stream<Arguments> retryAfterExamples() {
    return Stream.of(
        Arguments.of(Duration.ofNanos(1), "1"),
        Arguments.of(Duration.ofMillis(1500), "2"),
        Arguments.of(Duration.ofSeconds(2), "2"),
        Arguments.of(Duration.ZERO, "1"),
        Arguments.of(Duration.ofSeconds(-1), "1"),
        Arguments.of(null, "1"));
  }
}
