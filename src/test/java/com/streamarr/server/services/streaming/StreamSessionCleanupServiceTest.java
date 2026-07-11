package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

  private static final class RecordingLifecycle
      extends UnsupportedStreamSessionLifecycleTransactions {

    private final List<UUID> deletedIds = new ArrayList<>();

    @Override
    public boolean deleteTerminating(UUID streamSessionId) {
      deletedIds.add(streamSessionId);
      return true;
    }
  }
}
