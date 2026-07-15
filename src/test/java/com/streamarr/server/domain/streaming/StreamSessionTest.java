package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.buildMpegtsSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Stream Session Tests")
class StreamSessionTest {

  @Test
  @DisplayName("Should reject a null playback authority")
  void shouldRejectNullPlaybackAuthority() {
    var builder = StreamSession.builder();

    assertThatThrownBy(() -> builder.authority(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("authority");
  }

  @Test
  @DisplayName("Should reject direct mutation of the exposed variant handle map")
  void shouldRejectDirectMutationOfExposedVariantHandleMap() {
    var session = buildMpegtsSession();
    var handles = session.getVariantHandles();

    assertThatThrownBy(handles::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("Should reflect handle updates through the exposed variant handle map")
  void shouldReflectHandleUpdatesThroughExposedVariantHandleMap() {
    var session = buildMpegtsSession();

    session.setVariantHandle("720p", new TranscodeHandle(7L, TranscodeStatus.ACTIVE));

    assertThat(session.getVariantHandles()).containsKey("720p");
  }
}
