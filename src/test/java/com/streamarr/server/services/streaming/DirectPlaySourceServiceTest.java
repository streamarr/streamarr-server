package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.directPlaySessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.exceptions.FileShrankException;
import com.streamarr.server.fakes.FakeStreamingService;
import com.streamarr.server.properties.RemoteTranscodeProperties;
import com.sun.management.UnixOperatingSystemMXBean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
  private SimpleMeterRegistry meterRegistry;
  private DirectPlaySourceService sources;
  private Path directory;
  private Path outsideFile;
  private Path sourceFile;

  @BeforeEach
  void setUp() throws IOException {
    directory = Files.createDirectory(temporaryDirectory.resolve("media"));
    outsideFile = Files.write(temporaryDirectory.resolve("secret.mp4"), new byte[] {9});
    streamingService = new FakeStreamingService();
    meterRegistry = new SimpleMeterRegistry();
    sources =
        new DirectPlaySourceService(
            streamingService,
            new RemoteTranscodeProperties(UUID.randomUUID(), directory.toString()),
            new DirectPlayMetrics(meterRegistry));
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

    try (var source = open(session).orElseThrow()) {
      assertThat(source.container()).isEqualTo(SourceContainer.MOV);
      assertThat(source.length()).isEqualTo(3);
      assertThat(source.newInputStream().readAllBytes()).containsExactly(1, 2, 3);
    }
  }

  @Test
  @DisplayName("Should open the file when it is a hard link to another file")
  void shouldOpenTheFileWhenItIsHardLinkToAnotherFile() throws IOException {
    var hardLink = Files.createLink(directory.resolve("linked.mov"), sourceFile);
    var session = directPlaySessionBuilder().sourcePath(hardLink).build();
    streamingService.addSession(session);

    try (var source = open(session).orElseThrow()) {
      assertThat(source.newInputStream().readAllBytes()).containsExactly(1, 2, 3);
    }
  }

  @Test
  @DisplayName("Should keep reading the opened file when a folder on its path becomes a soft link")
  void shouldKeepReadingTheOpenedFileWhenFolderOnItsPathBecomesSoftLink() throws IOException {
    var show = Files.createDirectory(directory.resolve("show"));
    var otherShow = Files.createDirectory(directory.resolve("other-show"));
    var episode = Files.write(show.resolve("episode.mp4"), new byte[] {1, 2, 3});
    Files.write(otherShow.resolve("episode.mp4"), new byte[] {7, 8, 9});
    var session = directPlaySessionBuilder().sourcePath(episode).build();
    streamingService.addSession(session);

    try (var source = open(session).orElseThrow()) {
      Files.move(show, directory.resolve("show-before-swap"));
      Files.createSymbolicLink(show, otherShow);

      assertThat(source.newInputStream().readAllBytes()).containsExactly(1, 2, 3);
    }
  }

  @Test
  @DisplayName("Should end each stream at the length the file had when it opened")
  void shouldEndEachStreamAtTheLengthTheFileHadWhenItOpened() throws IOException {
    var session = directPlaySessionBuilder().sourcePath(sourceFile).build();
    streamingService.addSession(session);

    try (var source = open(session).orElseThrow()) {
      Files.write(sourceFile, new byte[] {4, 5}, StandardOpenOption.APPEND);

      assertThat(source.newInputStream().readAllBytes()).containsExactly(1, 2, 3);
    }
  }

  @Test
  @DisplayName("Should fail the read when the file ends before the length it had when it opened")
  void shouldFailTheReadWhenTheFileEndsBeforeTheLengthItHadWhenItOpened() throws IOException {
    var session = directPlaySessionBuilder().sourcePath(sourceFile).build();
    streamingService.addSession(session);

    try (var source = open(session).orElseThrow()) {
      Files.write(sourceFile, new byte[] {1});
      var stream = source.newInputStream();

      assertThatThrownBy(stream::readAllBytes).isInstanceOf(FileShrankException.class);
    }
  }

  @Test
  @DisplayName("Should read one byte at a time until the end when a reader asks for single bytes")
  void shouldReadOneByteAtATimeUntilTheEndWhenReaderAsksForSingleBytes() throws IOException {
    var session = directPlaySessionBuilder().sourcePath(sourceFile).build();
    streamingService.addSession(session);

    try (var source = open(session).orElseThrow()) {
      var stream = source.newInputStream();

      assertThat(new int[] {stream.read(), stream.read(), stream.read(), stream.read()})
          .containsExactly(1, 2, 3, -1);
    }
  }

  @Test
  @DisplayName("Should count the bytes read from the source when its streams are read")
  void shouldCountTheBytesReadFromTheSourceWhenItsStreamsAreRead() throws IOException {
    var session = directPlaySessionBuilder().sourcePath(sourceFile).build();
    streamingService.addSession(session);

    try (var source = open(session).orElseThrow()) {
      source.newInputStream().readAllBytes();
      var laterRange = source.newInputStream();
      laterRange.skipNBytes(2);
      laterRange.readAllBytes();
    }

    assertThat(meterRegistry.get("streamarr.streaming.direct_play_bytes_served").counter().count())
        .isEqualTo(4);
  }

  @Test
  @DisplayName("Should count an open response until the source closes when a source opens")
  void shouldCountAnOpenResponseUntilTheSourceClosesWhenSourceOpens() {
    var session = directPlaySessionBuilder().sourcePath(sourceFile).build();
    streamingService.addSession(session);

    var source = open(session).orElseThrow();
    assertThat(openResponses()).isEqualTo(1);

    source.close();
    source.close();
    assertThat(openResponses()).isZero();
  }

  @Test
  @DisplayName("Should answer nothing when the source file is a soft link inside the media root")
  void shouldAnswerNothingWhenSourceFileIsSoftLinkInsideTheMediaRoot() throws IOException {
    var link = Files.createSymbolicLink(directory.resolve("linked.mov"), sourceFile);
    var session = directPlaySessionBuilder().sourcePath(link).build();
    streamingService.addSession(session);

    assertThat(open(session)).isEmpty();
  }

  @Test
  @DisplayName("Should answer nothing when a parent folder is a soft link inside the media root")
  void shouldAnswerNothingWhenParentFolderIsSoftLinkInsideTheMediaRoot() throws IOException {
    var show = Files.createDirectory(directory.resolve("show"));
    Files.write(show.resolve("episode.mp4"), new byte[] {1, 2, 3});
    var linkedShow = Files.createSymbolicLink(directory.resolve("linked-show"), show);
    var session = directPlaySessionBuilder().sourcePath(linkedShow.resolve("episode.mp4")).build();
    streamingService.addSession(session);

    assertThat(open(session)).isEmpty();
  }

  @Test
  @DisplayName("Should leave no file open when a folder on the path is a regular file")
  void shouldLeaveNoFileOpenWhenFolderOnThePathIsRegularFile() throws IOException {
    var show = Files.write(directory.resolve("show"), new byte[] {1});
    var session = directPlaySessionBuilder().sourcePath(show.resolve("episode.mp4")).build();
    streamingService.addSession(session);
    var attempts = 100;
    var openBefore = openFileCount();

    for (var attempt = 0; attempt < attempts; attempt++) {
      assertThat(open(session)).isEmpty();
    }

    assertThat(openFileCount() - openBefore).isLessThan(attempts);
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
  @DisplayName("Should answer nothing when the source file lies outside the media root")
  void shouldAnswerNothingWhenSourceFileLiesOutsideTheMediaRoot() {
    var session = directPlaySessionBuilder().sourcePath(outsideFile).build();
    streamingService.addSession(session);

    assertThat(open(session)).isEmpty();
  }

  @Test
  @DisplayName("Should answer nothing when the source path climbs out of the media root")
  void shouldAnswerNothingWhenSourcePathClimbsOutOfTheMediaRoot() {
    var session = directPlaySessionBuilder().sourcePath(directory.resolve("../secret.mp4")).build();
    streamingService.addSession(session);

    assertThat(open(session)).isEmpty();
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
  @DisplayName("Should answer nothing when the source path is the media root")
  void shouldAnswerNothingWhenSourcePathIsTheMediaRoot() {
    var session = directPlaySessionBuilder().sourcePath(directory).build();
    streamingService.addSession(session);

    assertThat(open(session)).isEmpty();
  }

  @Test
  @DisplayName("Should answer nothing when the source path is a directory")
  void shouldAnswerNothingWhenSourcePathIsDirectory() throws IOException {
    var show = Files.createDirectory(directory.resolve("show"));
    var session = directPlaySessionBuilder().sourcePath(show).build();
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

  private static long openFileCount() {
    var system = ManagementFactory.getOperatingSystemMXBean();
    assertThat(system).isInstanceOf(UnixOperatingSystemMXBean.class);
    return ((UnixOperatingSystemMXBean) system).getOpenFileDescriptorCount();
  }

  private double openResponses() {
    return meterRegistry.get("streamarr.streaming.direct_play_open_responses").gauge().value();
  }

  private Optional<DirectPlaySource> open(StreamSession session) {
    return sources.open(session.getSessionId(), identityFor(session.getAuthority()));
  }
}
