package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.repositories.streaming.MediaStreamTermination;
import com.streamarr.server.repositories.streaming.PlaybackRequestAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Stream Session Cleanup Service Tests")
class StreamSessionCleanupServiceTest {

  @Test
  @DisplayName("Should retain durable marker while runtime starter is in flight")
  void shouldRetainDurableMarkerWhileRuntimeStarterIsInFlight() {
    var streamSessionId = UUID.randomUUID();
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    var reservation = runtimeRegistry.reserve(streamSessionId).orElseThrow();
    var starter = runtimeRegistry.beginTranscodeStart(streamSessionId).orElseThrow();
    var lifecycle = new RecordingLifecycle();
    var cleanupService =
        new StreamSessionCleanupService(
            runtimeService(runtimeRegistry),
            lifecycle,
            new StreamSessionTransactionRetry(_ -> {}),
            new MutexFactoryProvider());

    cleanupService.cleanup(streamSessionId);

    assertThat(lifecycle.deletedIds).isEmpty();

    runtimeRegistry.releaseReservation(reservation);
    assertThat(runtimeRegistry.completeTranscodeStart(starter)).isFalse();
    runtimeRegistry.finishRejectedTranscodeStart(starter, false);
    cleanupService.cleanup(streamSessionId);

    assertThat(lifecycle.deletedIds).containsExactly(streamSessionId);
  }

  private StreamingService runtimeService(RuntimeStreamSessionRegistry runtimeRegistry) {
    return new HlsStreamingService(
        new FakeMediaFileRepository(),
        new FakeTranscodeExecutor(),
        new FakeSegmentStore(),
        new FakeFfprobeService(),
        new TranscodeDecisionService(),
        new QualityLadderService(),
        StreamingProperties.builder().segmentDuration(Duration.ofSeconds(6)).build(),
        runtimeRegistry,
        new MutexFactory<>());
  }

  private static final class RecordingLifecycle implements StreamSessionLifecycleTransactions {

    private final List<UUID> deletedIds = new ArrayList<>();

    @Override
    public Optional<Instant> admit(
        StreamSessionAuthority authority, java.time.Duration provisioningTimeout) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean activate(
        StreamSessionAuthority authority, java.time.Duration provisioningTimeout) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Instant> touchIfPlaybackRequestMatches(PlaybackRequestAuthority authority) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<UUID> findTerminatingIds(int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<UUID> findTerminatingIdsAfter(UUID afterId, int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<UUID> terminalizeByMediaFiles(MediaStreamTermination termination) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<UUID> terminalizeMissingMediaSources(Instant terminalAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean terminalize(StreamSessionTermination termination) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean recordTerminationIntent(StreamSessionTermination termination) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<StreamSessionTermination> findTerminationIntents() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean completeCreation(UUID streamSessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean replayTerminationIntent(UUID streamSessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteTerminationIntent(UUID streamSessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteTerminating(UUID streamSessionId) {
      deletedIds.add(streamSessionId);
      return true;
    }
  }
}
