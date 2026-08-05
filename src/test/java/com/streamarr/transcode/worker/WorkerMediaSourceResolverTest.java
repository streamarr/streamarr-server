package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.v1.MediaSourceRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Worker Media Source Resolver Tests")
class WorkerMediaSourceResolverTest {

  private static final UUID SOURCE_NAMESPACE_ID = UUID.randomUUID();

  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(strings = {"", "/movie.mkv"})
  @DisplayName("Should reject an empty or leading-slash media source key when resolving a source")
  void shouldRejectEmptyOrLeadingSlashMediaSourceKeyWhenResolvingSource(String relativeKey)
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var mediaSource = source(relativeKey);

    assertThatThrownBy(() -> resolver.resolve(mediaSource))
        .isInstanceOf(WorkerJobException.class)
        .hasMessage("Media source key contains an unsafe path segment");
  }

  @Test
  @DisplayName("Should reject a relative key with an empty path segment when resolving a source")
  void shouldRejectRelativeKeyWithEmptyPathSegmentWhenResolvingSource() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var nested = Files.createDirectory(mediaRoot.resolve("nested"));
    Files.writeString(nested.resolve("movie.mkv"), "test media");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("nested//movie.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName("Should reject a relative key with a dot path segment when resolving a source")
  void shouldRejectRelativeKeyWithDotPathSegmentWhenResolvingSource() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var nested = Files.createDirectory(mediaRoot.resolve("nested"));
    Files.writeString(nested.resolve("movie.mkv"), "test media");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("nested/./movie.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName(
      "Should reject a relative key with a platform-specific separator when resolving a source")
  void shouldRejectRelativeKeyWithPlatformSpecificSeparatorWhenResolvingSource() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("nested\\movie.mkv"), "test media");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("nested\\movie.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName("Should reject a relative key with a Windows drive prefix when resolving a source")
  void shouldRejectRelativeKeyWithWindowsDrivePrefixWhenResolvingSource() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var windows = Files.createDirectories(mediaRoot.resolve("C:/Windows"));
    Files.writeString(windows.resolve("system.ini"), "test data");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("C:/Windows/system.ini");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName(
      "Should resolve Unicode and percent sequences as literal key data when resolving a source")
  void shouldResolveUnicodeAndPercentSequencesAsLiteralKeyDataWhenResolvingSource()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var nested = Files.createDirectories(mediaRoot.resolve("日本語/%2e%2e"));
    var mediaFile = Files.writeString(nested.resolve("映画.mkv"), "test media");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));

    assertThat(resolver.resolve(source("日本語/%2e%2e/映画.mkv"))).isEqualTo(mediaFile.toRealPath());
  }

  @Test
  @DisplayName("Should reject a relative key containing NUL when resolving a source")
  void shouldRejectRelativeKeyContainingNulWhenResolvingSource() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("movie\0.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName("Should reject an unknown source namespace when resolving a source")
  void shouldRejectUnknownSourceNamespaceWhenResolvingSource() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var unknownSource =
        MediaSourceRef.newBuilder()
            .setSourceNamespaceId(toProto(UUID.randomUUID()))
            .setRelativeKey("movie.mkv")
            .build();

    assertThatThrownBy(() -> resolver.resolve(unknownSource))
        .isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName("Should report an unavailable media source when resolving a source")
  void shouldReportUnavailableMediaSourceWhenResolvingSource() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var unavailableSource = source("x");

    assertThatThrownBy(() -> resolver.resolve(unavailableSource))
        .isInstanceOf(WorkerJobException.class)
        .hasMessage("Media source is unavailable")
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("Should reject a directory as a media source when resolving a source")
  void shouldRejectDirectoryAsMediaSourceWhenResolvingSource() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.createDirectory(mediaRoot.resolve("folder"));
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var directorySource = source("folder");

    assertThatThrownBy(() -> resolver.resolve(directorySource))
        .isInstanceOf(WorkerJobException.class)
        .hasMessage("Media source is not a readable file in its namespace");
  }

  @Test
  @DisplayName("Should reject a key that escapes its source namespace when resolving a source")
  void shouldRejectKeyThatEscapesItsSourceNamespaceWhenResolvingSource() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(tempDir.resolve("secret.mkv"), "secret");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("../secret.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName(
      "Should reject a symlink whose target escapes its source namespace when resolving a source")
  void shouldRejectSymlinkWhoseTargetEscapesItsSourceNamespaceWhenResolvingSource()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var secret = Files.writeString(tempDir.resolve("secret.mkv"), "secret");
    Files.createSymbolicLink(mediaRoot.resolve("movie.mkv"), secret);
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("movie.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName(
      "Should resolve a symlink whose target remains inside its source namespace when resolving a source")
  void shouldResolveSymlinkWhoseTargetRemainsInsideSourceNamespaceWhenResolvingSource()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var target = Files.writeString(mediaRoot.resolve("stored-movie.mkv"), "test media");
    Files.createSymbolicLink(mediaRoot.resolve("movie.mkv"), target);
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));

    assertThat(resolver.resolve(source("movie.mkv"))).isEqualTo(target.toRealPath());
  }

  private MediaSourceRef source(String relativeKey) {
    return MediaSourceRef.newBuilder()
        .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
        .setRelativeKey(relativeKey)
        .build();
  }
}
