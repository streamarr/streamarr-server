package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.ServerSettingsFixture.defaultServerSettings;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Session Reaper Disk Pressure Tests")
class SessionReaperDiskPressureTest {

  // The folder's file system holds this many bytes; the stored segments fill part of it.
  private static final long CAPACITY_BYTES = 1_000;

  @TempDir private Path segmentFolder;

  private final MutableClock clock = new MutableClock();
  private final FakeRuntimeStreamSessionRegistry registry = new FakeRuntimeStreamSessionRegistry();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private LocalSegmentStore segmentStore;
  private HlsStreamingService streamingService;
  private final FakeTranscodeExecutor executor = new FakeTranscodeExecutor();
  private ProducerLifecycleService producerLifecycle;

  @BeforeEach
  void setUp() {
    segmentStore = new LocalSegmentStore(segmentFolder);
    var properties =
        StreamingProperties.builder()
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(executor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(registry)
            .heldRequests(executor.heldSegmentRequests())
            .build();
    producerLifecycle = rig.lifecycle();
    streamingService =
        HlsStreamingService.builder()
            .mediaFileRepository(new FakeMediaFileRepository())
            .transcodeExecutor(executor)
            .segmentStore(segmentStore)
            .playbackProbeService(
                playbackProbeServiceBuilder(new FakeMediaFileContainerInfoRepository()).build())
            .transcodeDecisionService(new TranscodeDecisionService(properties, meters))
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate(new FakePlaybackAuthorityGate())
            .runtimeRegistry(registry)
            .producerLifecycle(producerLifecycle)
            .deliveryCoordinator(rig.coordinator())
            .meterRegistry(meters)
            .build();
  }

  @Test
  @DisplayName(
      "Should delete the oldest idle sessions until a tenth of the disk is free when the segment"
          + " folder's disk is nearly full")
  void shouldDeleteOldestIdleSessionsUntilTenthOfDiskIsFreeWhenSegmentFolderDiskIsNearlyFull() {
    var idleFiveMinutes = storedSession(Duration.ofMinutes(5), 400);
    var oldest = storedSession(Duration.ofMinutes(50), 40);
    var active = storedSession(Duration.ofSeconds(10), 470);
    var idleThirtyMinutes = storedSession(Duration.ofMinutes(30), 40);

    reaperReading(new StoredSegmentBytes(segmentFolder)).reapSessions();

    assertThat(streamingService.getAllSessions())
        .extracting(StreamSession::getSessionId)
        .containsExactlyInAnyOrder(idleFiveMinutes, active);
    assertThat(segmentFolder.resolve(oldest.toString())).doesNotExist();
    assertThat(segmentFolder.resolve(idleThirtyMinutes.toString())).doesNotExist();
    assertThat(segmentFolder.resolve(idleFiveMinutes.toString())).exists();
    assertThat(segmentFolder.resolve(active.toString())).exists();
    assertThat(meters.counter("streamarr.streaming.disk_pressure_evictions").count()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should keep every active session when the segment folder's disk stays nearly full")
  void shouldKeepEveryActiveSessionWhenSegmentFolderDiskStaysNearlyFull() {
    var first = storedSession(Duration.ofSeconds(10), 470);
    var second = storedSession(Duration.ofSeconds(50), 480);

    reaperReading(new StoredSegmentBytes(segmentFolder)).reapSessions();

    assertThat(streamingService.getAllSessions())
        .extracting(StreamSession::getSessionId)
        .containsExactlyInAnyOrder(first, second);
    assertThat(segmentFolder.resolve(first.toString())).exists();
    assertThat(segmentFolder.resolve(second.toString())).exists();
    assertThat(meters.counter("streamarr.streaming.disk_pressure_evictions").count()).isZero();
  }

  @Test
  @DisplayName("Should keep idle sessions when a tenth of the segment folder's disk is free")
  void shouldKeepIdleSessionsWhenTenthOfSegmentFolderDiskIsFree() {
    var oldest = storedSession(Duration.ofMinutes(50), 450);
    var newest = storedSession(Duration.ofMinutes(5), 450);

    reaperReading(new StoredSegmentBytes(segmentFolder)).reapSessions();

    assertThat(streamingService.getAllSessions())
        .extracting(StreamSession::getSessionId)
        .containsExactlyInAnyOrder(oldest, newest);
    assertThat(meters.counter("streamarr.streaming.disk_pressure_evictions").count()).isZero();
  }

  @Test
  @DisplayName(
      "Should keep an idle session and evict the next when a request reaches it during eviction")
  void shouldKeepIdleSessionAndEvictNextWhenRequestReachesItDuringEviction() {
    var resumed = storedSession(Duration.ofMinutes(50), 450);
    var next = storedSession(Duration.ofMinutes(30), 450);
    var segmentBytes = new StoredSegmentBytes(segmentFolder);
    SegmentFolderSpace resumeBeforeFirstRead =
        () -> {
          registry.touch(resumed, clock.instant());
          return segmentBytes.freeFraction() - 0.05;
        };

    reaperReading(resumeBeforeFirstRead).reapSessions();

    assertThat(streamingService.getAllSessions())
        .extracting(StreamSession::getSessionId)
        .containsExactly(resumed);
    assertThat(segmentFolder.resolve(next.toString())).doesNotExist();
    assertThat(meters.counter("streamarr.streaming.disk_pressure_evictions").count()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should keep an idle session and evict the next when a request reaches it just before its"
          + " removal")
  void shouldKeepIdleSessionAndEvictNextWhenRequestReachesItJustBeforeItsRemoval() {
    var resumed = storedSession(Duration.ofMinutes(50), 450);
    var next = storedSession(Duration.ofMinutes(30), 450);
    registry.beforeNextRemoval(() -> registry.touch(resumed, clock.instant()));
    SegmentFolderSpace nearlyFull =
        () -> new StoredSegmentBytes(segmentFolder).freeFraction() - 0.05;

    reaperReading(nearlyFull).reapSessions();

    assertThat(streamingService.getAllSessions())
        .extracting(StreamSession::getSessionId)
        .containsExactly(resumed);
    assertThat(segmentFolder.resolve(resumed.toString())).exists();
    assertThat(segmentFolder.resolve(next.toString())).doesNotExist();
    assertThat(meters.counter("streamarr.streaming.disk_pressure_evictions").count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should keep evicting the next idle session when destroying one fails")
  void shouldKeepEvictingNextIdleSessionWhenDestroyingOneFails() {
    var failing = storedSession(Duration.ofMinutes(50), 40);
    var next = storedSession(Duration.ofMinutes(30), 40);
    var idleFiveMinutes = storedSession(Duration.ofMinutes(5), 400);
    var active = storedSession(Duration.ofSeconds(10), 470);
    executor.failOnStop(failing);

    reaperReading(new StoredSegmentBytes(segmentFolder)).reapSessions();

    assertThat(streamingService.getAllSessions())
        .extracting(StreamSession::getSessionId)
        .containsExactlyInAnyOrder(idleFiveMinutes, active);
    assertThat(segmentFolder.resolve(next.toString())).doesNotExist();
    assertThat(meters.counter("streamarr.streaming.disk_pressure_evictions").count()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should still destroy expired sessions and count the failure when the free space cannot be"
          + " read")
  void shouldStillDestroyExpiredSessionsAndCountFailureWhenFreeSpaceCannotBeRead() {
    var expired = storedSession(Duration.ofMinutes(61), 100);
    var idle = storedSession(Duration.ofMinutes(5), 100);

    reaperReading(
            () -> {
              throw new UncheckedIOException(new IOException("file system unavailable"));
            })
        .reapSessions();

    assertThat(streamingService.getAllSessions())
        .extracting(StreamSession::getSessionId)
        .containsExactly(idle);
    assertThat(segmentFolder.resolve(expired.toString())).doesNotExist();
    assertThat(meters.counter("streamarr.streaming.disk_space_read_failures").count()).isEqualTo(1);
  }

  private SessionReaper reaperReading(SegmentFolderSpace space) {
    return SessionReaper.builder()
        .streamingService(streamingService)
        .properties(
            StreamingProperties.builder()
                .targetSegmentDuration(Duration.ofSeconds(6))
                .sessionTimeout(Duration.ofSeconds(60))
                .build())
        .producerLifecycle(producerLifecycle)
        .playbackSessionService(playbackServiceBuilder(streamingService).clock(clock).build())
        .serverSettings(defaultServerSettings())
        .segmentFolderSpace(space)
        .meterRegistry(meters)
        .clock(clock)
        .build();
  }

  private UUID storedSession(Duration idleFor, int segmentBytes) {
    var session = StreamSessionFixture.buildActiveSession();
    session.setLastAccessedAt(clock.instant().minus(idleFor));
    registry.save(session);
    segmentStore.storeSegment(session.getSessionId(), "segment0.m4s", new byte[segmentBytes]);
    return session.getSessionId();
  }

  /** Reads the free share of a file system that holds only the segment folder's files. */
  private record StoredSegmentBytes(Path folder) implements SegmentFolderSpace {

    @Override
    public double freeFraction() {
      try (var files = Files.walk(folder)) {
        var used = files.filter(Files::isRegularFile).mapToLong(StoredSegmentBytes::size).sum();
        return (double) (CAPACITY_BYTES - used) / CAPACITY_BYTES;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    private static long size(Path file) {
      try {
        return Files.size(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
