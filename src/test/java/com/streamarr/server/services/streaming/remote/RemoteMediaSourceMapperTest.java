package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.TranscodeException;
import java.net.URI;
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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Café Meridian (2006)/Café Meridian (2006).mkv",
        "Amélie’s Journey.mkv",
        "千と千尋の神隠し (2001)/千と千尋の神隠し.mkv",
        "기생충 (2019).mkv",
        "Treble 𝄞 Clef 🎬.mkv",
        "Ame\u0301lie (2001).mkv",
        "100%23 Legit + Bonus #1.mkv",
        "Tricky %2F ..%2F %2E%2E name.mkv"
      })
  @DisplayName("Should key the source by its literal names when they contain Unicode or percents")
  void shouldKeySourceByItsLiteralNamesWhenTheyContainUnicodeOrPercents(String relativePath) {
    var source = mapper.map(SOURCE_ROOT.resolve(relativePath));

    assertThat(source.getRelativeKey()).isEqualTo(relativePath);
  }

  @Test
  @DisplayName("Should reject the source when its name is not valid UTF-8")
  void shouldRejectSourceWhenItsNameIsNotValidUtf8() {
    var source = Path.of(URI.create("file:///media/movies/caf%E9.mkv"));

    assertThatThrownBy(() -> mapper.map(source))
        .isExactlyInstanceOf(TranscodeException.class)
        .hasMessage("Media source name is not valid UTF-8");
  }
}
