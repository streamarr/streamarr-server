package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Matroska Statistics Tests")
class MatroskaStatisticsTest {

  @Test
  @DisplayName("Should refuse statistics when the stream carries neither tag")
  void shouldRefuseStatisticsWhenTheStreamCarriesNeitherTag() {
    var numberOfFrames = OptionalLong.empty();
    var duration = Optional.<Duration>empty();

    assertThatThrownBy(() -> new MatroskaStatistics(numberOfFrames, duration))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should keep the statistics when the stream carries only its duration tag")
  void shouldKeepTheStatisticsWhenTheStreamCarriesOnlyItsDurationTag() {
    var statistics =
        new MatroskaStatistics(OptionalLong.empty(), Optional.of(Duration.ofSeconds(120)));

    assertThat(statistics.duration()).contains(Duration.ofSeconds(120));
  }
}
