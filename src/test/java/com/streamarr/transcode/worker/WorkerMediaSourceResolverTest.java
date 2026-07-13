package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.Uuid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Worker Media Source Resolver Tests")
class WorkerMediaSourceResolverTest {

  private static final UUID SOURCE_NAMESPACE_ID = UUID.randomUUID();

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should reject a relative key with an empty path segment")
  void shouldRejectRelativeKeyWithEmptyPathSegment() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var nested = Files.createDirectory(mediaRoot.resolve("nested"));
    Files.writeString(nested.resolve("movie.mkv"), "test media");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("nested//movie.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName("Should reject a relative key with a dot path segment")
  void shouldRejectRelativeKeyWithDotPathSegment() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var nested = Files.createDirectory(mediaRoot.resolve("nested"));
    Files.writeString(nested.resolve("movie.mkv"), "test media");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("nested/./movie.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName("Should reject a relative key with a platform-specific separator")
  void shouldRejectRelativeKeyWithPlatformSpecificSeparator() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("nested\\movie.mkv"), "test media");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("nested\\movie.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName("Should reject a relative key with a Windows drive prefix")
  void shouldRejectRelativeKeyWithWindowsDrivePrefix() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var windows = Files.createDirectories(mediaRoot.resolve("C:/Windows"));
    Files.writeString(windows.resolve("system.ini"), "test data");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("C:/Windows/system.ini");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName("Should resolve Unicode and percent sequences as literal key data")
  void shouldResolveUnicodeAndPercentSequencesAsLiteralKeyData() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var nested = Files.createDirectories(mediaRoot.resolve("日本語/%2e%2e"));
    var mediaFile = Files.writeString(nested.resolve("映画.mkv"), "test media");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));

    assertThat(resolver.resolve(source("日本語/%2e%2e/映画.mkv"))).isEqualTo(mediaFile.toRealPath());
  }

  @Test
  @DisplayName("Should reject a relative key containing NUL")
  void shouldRejectRelativeKeyContainingNul() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("movie\0.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName("Should reject an unknown source namespace")
  void shouldRejectUnknownSourceNamespace() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var unknownSource =
        MediaSourceRef.newBuilder()
            .setSourceNamespaceId(uuid(UUID.randomUUID()))
            .setRelativeKey("movie.mkv")
            .build();

    assertThatThrownBy(() -> resolver.resolve(unknownSource))
        .isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName("Should report an unavailable media source")
  void shouldReportUnavailableMediaSource() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var unavailableSource = source("x");

    assertThatThrownBy(() -> resolver.resolve(unavailableSource))
        .isInstanceOf(WorkerJobException.class)
        .hasMessage("Media source is unavailable")
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("Should reject a directory as a media source")
  void shouldRejectDirectoryAsMediaSource() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.createDirectory(mediaRoot.resolve("folder"));
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var directorySource = source("folder");

    assertThatThrownBy(() -> resolver.resolve(directorySource))
        .isInstanceOf(WorkerJobException.class)
        .hasMessage("Media source is not a readable file in its namespace");
  }

  @Test
  @DisplayName("Should reject a key that escapes its source namespace")
  void shouldRejectKeyThatEscapesItsSourceNamespace() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(tempDir.resolve("secret.mkv"), "secret");
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("../secret.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  @Test
  @DisplayName("Should reject a symlink whose target escapes its source namespace")
  void shouldRejectSymlinkWhoseTargetEscapesItsSourceNamespace() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var secret = Files.writeString(tempDir.resolve("secret.mkv"), "secret");
    Files.createSymbolicLink(mediaRoot.resolve("movie.mkv"), secret);
    var resolver = new WorkerMediaSourceResolver(Map.of(SOURCE_NAMESPACE_ID, mediaRoot));
    var source = source("movie.mkv");

    assertThatThrownBy(() -> resolver.resolve(source)).isInstanceOf(WorkerJobException.class);
  }

  private MediaSourceRef source(String relativeKey) {
    return MediaSourceRef.newBuilder()
        .setSourceNamespaceId(uuid(SOURCE_NAMESPACE_ID))
        .setRelativeKey(relativeKey)
        .build();
  }

  private Uuid uuid(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }
}
