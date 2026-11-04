package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.directPlaySessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.RemoteTranscodeProperties;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakeStreamingService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Direct Play Source Service Tests")
class DirectPlaySourceServiceTest {

  @TempDir private Path temporaryDirectory;

  private FakeStreamingService streamingService;
  private DirectPlaySourceService sources;
  private Path directory;
  private Path outsideFile;
  private Path sourceFile;

  @BeforeEach
  void setUp() throws IOException {
    directory = Files.createDirectory(temporaryDirectory.resolve("media"));
    outsideFile = Files.write(temporaryDirectory.resolve("secret.mp4"), new byte[] {9});
    streamingService = new FakeStreamingService();
    sources =
        new DirectPlaySourceService(
            streamingService,
            new RemoteTranscodeProperties(UUID.randomUUID(), directory.toString()));
    sourceFile = Files.write(directory.resolve("movie.mov"), new byte[] {1, 2, 3});
  }

  @Test
  @DisplayName("Should open the source file and its container when the session direct-plays")
  void shouldOpenSourceFileAndItsContainerWhenSessionDirectPlays() throws IOException {
    var session =
        directPlaySessionBuilder()
            .sourcePath(sourceFile)
            .sourceContainer(SourceContainer.MOV)
            .build();
    streamingService.addSession(session);

    var source = open(session);

    assertThat(source).contains(new DirectPlaySource(sourceFile.toRealPath(), SourceContainer.MOV));
  }

  @Test
  @DisplayName("Should answer nothing when the source file links outside the media root")
  void shouldAnswerNothingWhenSourceFileLinksOutsideTheMediaRoot() throws IOException {
    var link = Files.createSymbolicLink(directory.resolve("linked.mp4"), outsideFile);
    var session = directPlaySessionBuilder().sourcePath(link).build();
    streamingService.addSession(session);

    assertThat(open(session)).isEmpty();
  }

  @Test
  @DisplayName("Should answer nothing when a parent directory links outside the media root")
  void shouldAnswerNothingWhenParentDirectoryLinksOutsideTheMediaRoot() throws IOException {
    var linkedDirectory = Files.createSymbolicLink(directory.resolve("shows"), temporaryDirectory);
    var session =
        directPlaySessionBuilder().sourcePath(linkedDirectory.resolve("secret.mp4")).build();
    streamingService.addSession(session);

    assertThat(open(session)).isEmpty();
  }

  @Test
  @DisplayName("Should open the link's target when a source link stays inside the media root")
  void shouldOpenTheLinksTargetWhenSourceLinkStaysInsideTheMediaRoot() throws IOException {
    var link = Files.createSymbolicLink(directory.resolve("linked.mov"), sourceFile);
    var session =
        directPlaySessionBuilder().sourcePath(link).sourceContainer(SourceContainer.MOV).build();
    streamingService.addSession(session);

    assertThat(open(session))
        .contains(new DirectPlaySource(sourceFile.toRealPath(), SourceContainer.MOV));
  }

  @Test
  @DisplayName("Should answer nothing when the session delivers HLS")
  void shouldAnswerNothingWhenSessionDeliversHls() {
    var session =
        defaultSessionBuilder().sourcePath(sourceFile).sourceContainer(SourceContainer.MP4).build();
    streamingService.addSession(session);

    assertThat(open(session)).isEmpty();
  }

  @Test
  @DisplayName("Should answer nothing when the source file no longer exists")
  void shouldAnswerNothingWhenSourceFileNoLongerExists() throws IOException {
    var session = directPlaySessionBuilder().sourcePath(sourceFile).build();
    streamingService.addSession(session);
    Files.delete(sourceFile);

    assertThat(open(session)).isEmpty();
  }

  @Test
  @DisplayName("Should answer nothing when the source path is a directory")
  void shouldAnswerNothingWhenSourcePathIsDirectory() {
    var session = directPlaySessionBuilder().sourcePath(directory).build();
    streamingService.addSession(session);

    assertThat(open(session)).isEmpty();
  }

  @Test
  @DisplayName("Should answer nothing when the direct-play session names no source container")
  void shouldAnswerNothingWhenDirectPlaySessionNamesNoSourceContainer() {
    var session = directPlaySessionBuilder().sourcePath(sourceFile).sourceContainer(null).build();
    streamingService.addSession(session);

    assertThat(open(session)).isEmpty();
  }

  private Optional<DirectPlaySource> open(StreamSession session) {
    return sources.open(session.getSessionId(), identityFor(session.getAuthority()));
  }
}
