package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe optional properties")
class ProbeOptionalPropertiesTest {

  @Test
  @DisplayName("Should keep container properties empty when omitted and reject null optionals")
  void shouldKeepContainerPropertiesEmptyWhenOmittedAndRejectNullOptionals() {
    var builder = ProbeContainer.builder();
    var container = builder.build();

    assertThat(container.format()).isEmpty();
    assertThat(container.duration()).isEmpty();
    assertThat(container.bitrate()).isEmpty();
    assertThatNullPointerException().isThrownBy(() -> builder.format(null));
    assertThatNullPointerException().isThrownBy(() -> builder.duration(null));
    assertThatNullPointerException().isThrownBy(() -> builder.bitrate(null));
  }

  @Test
  @DisplayName("Should keep stream properties empty when omitted and reject null optionals")
  void shouldKeepStreamPropertiesEmptyWhenOmittedAndRejectNullOptionals() {
    var builder = StreamInfo.builder().codecType("video");
    var stream = builder.build();

    assertThat(stream.codec()).isEmpty();
    assertThat(stream.language()).isEmpty();
    assertThat(stream.channels()).isEmpty();
    assertThat(stream.bitrate()).isEmpty();
    assertThat(stream.width()).isEmpty();
    assertThat(stream.height()).isEmpty();
    assertThat(stream.framerate()).isEmpty();
    assertThatNullPointerException().isThrownBy(() -> builder.codec(null));
    assertThatNullPointerException().isThrownBy(() -> builder.language(null));
    assertThatNullPointerException().isThrownBy(() -> builder.channels(null));
    assertThatNullPointerException().isThrownBy(() -> builder.bitrate(null));
    assertThatNullPointerException().isThrownBy(() -> builder.width(null));
    assertThatNullPointerException().isThrownBy(() -> builder.height(null));
    assertThatNullPointerException().isThrownBy(() -> builder.framerate(null));
  }
}
