package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.fakes.CapturingKeyframePacketScanRequests;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Keyframe packet scan with the worker image")
class KeyframePacketScanWorkerIT extends AbstractIntegrationTest {

  // A 4 s clip at 24 fps with a keyframe every 24 frames holds keyframes at 0, 1, 2 and 3 s.
  private static final List<String> MPEG_TS_SOURCE =
      List.of(
          "-f",
          "lavfi",
          "-i",
          "testsrc=size=64x36:rate=24:duration=4",
          "-c:v",
          "libx264",
          "-g",
          "24",
          "-keyint_min",
          "24",
          "-sc_threshold",
          "0",
          "-f",
          "mpegts");

  @TempDir Path mediaRoot;

  @Autowired private ProbeExecution probeExecution;
  @Autowired private PersistedProbeReader reader;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryRepository libraries;
  @Autowired private ProbeSchedulingProperties probeScheduling;

  private MediaFile mediaFile;
  private Library library;

  @AfterEach
  void removeMediaFile() {
    if (mediaFile != null) {
      mediaFiles.delete(mediaFile);
    }

    if (library != null) {
      libraries.delete(library);
    }
  }

  @Test
  @DisplayName(
      "Should store scanned keyframe positions when the probe of an MPEG-TS source asks for a"
          + " packet scan")
  void shouldStoreScannedKeyframePositionsWhenTheProbeOfAnMpegTsSourceAsksForAPacketScan()
      throws Exception {
    try (var server =
            new WorkerSessionServer(
                serverConfigurationBuilder().build(),
                new FakeSegmentStore(),
                new SimpleMeterRegistry());
        var worker =
            WorkerContainerFixture.builder()
                .workerSessions(server)
                .sourceNamespaceId(SOURCE_NAMESPACE_ID)
                .sourceRoot(mediaRoot)
                .build()) {
      server.start();
      worker.start();
      var source = worker.generateMedia(mediaRoot.resolve("keyframes.ts"), MPEG_TS_SOURCE);
      var request = saveMediaFile(source);
      var remote = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var packetScans = new CapturingKeyframePacketScanRequests();

      probeExecution.toBuilder().producer(remote).packetScans(packetScans).build().execute(request);

      assertThat(storedKeyframes())
          .isEqualTo(
              new KeyframeKnowledge.PacketScanRequired(
                  KeyframePacketScanReason.NO_CONTAINER_INDEX));
      assertThat(packetScans.requests()).hasSize(1);

      var result =
          KeyframePacketScanExecution.builder()
              .reader(reader)
              .scanner(remote::scanKeyframePackets)
              .fileSystem(FileSystems.getDefault())
              .outcomes(outcomes)
              .properties(probeScheduling)
              .build()
              .execute(packetScans.requests().getFirst());

      assertThat(result).isEqualTo(new KeyframePacketScanResult.Completed());
      assertThat(storedKeyframes())
          .isInstanceOfSatisfying(
              KeyframeKnowledge.Known.class,
              known -> {
                var positions = known.positions();
                var ticksPerSecond =
                    (long) positions.timeBaseDenominator() / positions.timeBaseNumerator();
                assertThat(positions.source()).isEqualTo(KeyframePositionSource.PACKET_SCAN);
                assertThat(positions.keyframes())
                    .extracting(KeyframePosition::presentationTime)
                    .as("one keyframe at the start of each second, on the zero-based timeline")
                    .containsExactly(
                        firstPresentationTime(positions.keyframes()),
                        firstPresentationTime(positions.keyframes()) + ticksPerSecond,
                        firstPresentationTime(positions.keyframes()) + 2 * ticksPerSecond,
                        firstPresentationTime(positions.keyframes()) + 3 * ticksPerSecond);
                assertThat(firstPresentationTime(positions.keyframes()))
                    .isBetween(0L, ticksPerSecond / 10);
              });
    }
  }

  private ProbeTaskRequest saveMediaFile(Path source) throws Exception {
    library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    mediaFile =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename(source.getFileName().toString())
                .filepathUri(FilepathCodec.encode(source))
                .size(Files.size(source))
                .build());
    var attributes = Files.readAttributes(source, BasicFileAttributes.class);
    return ProbeTaskRequest.builder()
        .mediaFileId(mediaFile.getId())
        .libraryId(library.getId())
        .filepathUri(mediaFile.getFilepathUri())
        .snapshot(
            new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant()))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }

  private KeyframeKnowledge storedKeyframes() {
    return outcomes.findKeyframeKnowledge(mediaFile.getId()).orElseThrow();
  }

  private static long firstPresentationTime(List<KeyframePosition> keyframes) {
    return keyframes.getFirst().presentationTime();
  }
}
