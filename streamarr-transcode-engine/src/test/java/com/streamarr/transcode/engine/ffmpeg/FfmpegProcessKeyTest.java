package com.streamarr.transcode.engine.ffmpeg;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("FFmpeg Process Key Tests")
class FfmpegProcessKeyTest {

  @ParameterizedTest
  @MethodSource("invalidKeys")
  @DisplayName("Should reject process key when exact job or rendition identity is invalid")
  void shouldRejectProcessKeyWhenExactJobOrRenditionIdentityIsInvalid(
      TranscodeJobRef jobRef, String renditionLabel) {
    assertThatThrownBy(() -> new FfmpegProcessKey(jobRef, renditionLabel))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> invalidKeys() {
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 1);
    return Stream.of(
        Arguments.of(null, "720p"),
        Arguments.of(jobRef, null),
        Arguments.of(jobRef, " "),
        Arguments.of(jobRef, "."),
        Arguments.of(jobRef, ".."),
        Arguments.of(jobRef, "nested/720p"),
        Arguments.of(jobRef, "nested\\720p"),
        Arguments.of(jobRef, "720p\0escape"));
  }
}
