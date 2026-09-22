package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.TranscodeException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Remote Media Source Mapper Tests")
class RemoteMediaSourceMapperTest {

  private static final Path SOURCE_ROOT = Path.of("/media/movies");

  private final RemoteMediaSourceMapper mapper =
      new RemoteMediaSourceMapper(SOURCE_NAMESPACE_ID, SOURCE_ROOT);

  @Test
  @DisplayName("Should key the source relative to the root when the source is nested")
  void shouldKeySourceRelativeToRootWhenSourceIsNested() {
    var source = mapper.map(SOURCE_ROOT.resolve("Feature (2020)/Feature (2020).mkv"));

    assertThat(source.getSourceNamespaceId()).isEqualTo(toProto(SOURCE_NAMESPACE_ID));
    assertThat(source.getRelativeKey()).isEqualTo("Feature (2020)/Feature (2020).mkv");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/media/movies", "/media/movies/../outside.mkv", "/media/movies-extra/film.mkv"})
  @DisplayName("Should reject the source when it is not a file below the root")
  void shouldRejectSourceWhenItIsNotFileBelowRoot(String sourcePath) {
    var source = Path.of(sourcePath);

    assertThatThrownBy(() -> mapper.map(source))
        .isExactlyInstanceOf(TranscodeException.class)
        .hasMessage("Media source is outside the configured source namespace");
  }
}
