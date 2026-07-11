package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Default Playback Session Termination Service Tests")
class DefaultPlaybackSessionTerminationServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-10T20:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("Should leave durable and runtime state unchanged for wrong owner")
  void shouldLeaveDurableAndRuntimeStateUnchangedForWrongOwner() {
    var session = StreamSessionFixture.buildMpegtsSession();
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    runtimeRegistry.save(session);
    var lifecycle = new RecordingLifecycle(false);
    var cleanup = new RecordingCleanup();
    var service = service(runtimeRegistry, lifecycle, cleanup);

    service.destroy(session.getSessionId(), UUID.randomUUID());

    assertThat(lifecycle.terminations).isEmpty();
    assertThat(cleanup.cleanedIds).isEmpty();
    assertThat(runtimeRegistry.findById(session.getSessionId())).contains(session);
  }

  @Test
  @DisplayName("Should retain runtime when durable owner termination retries exhaust")
  void shouldRetainRuntimeWhenDurableOwnerTerminationRetriesExhaust() {
    var session = StreamSessionFixture.buildMpegtsSession();
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    runtimeRegistry.save(session);
    var lifecycle = new RecordingLifecycle(true);
    var cleanup = new RecordingCleanup();
    var service = service(runtimeRegistry, lifecycle, cleanup);
    var streamSessionId = session.getSessionId();
    var profileId = session.getProfileId();

    assertThatThrownBy(() -> service.destroy(streamSessionId, profileId))
        .isInstanceOf(IllegalStateException.class);

    assertThat(lifecycle.terminalAttempts).isEqualTo(3);
    assertThat(cleanup.cleanedIds).isEmpty();
    assertThat(runtimeRegistry.findById(session.getSessionId())).contains(session);
  }

  private DefaultPlaybackSessionTerminationService service(
      RuntimeStreamSessionRegistry runtimeRegistry,
      StreamSessionLifecycleTransactions lifecycle,
      StreamSessionCleanup cleanup) {
    return new DefaultPlaybackSessionTerminationService(
        runtimeRegistry, lifecycle, cleanup, new StreamSessionTransactionRetry(_ -> {}), CLOCK);
  }

  private static final class RecordingCleanup implements StreamSessionCleanup {

    private final List<UUID> cleanedIds = new ArrayList<>();

    @Override
    public void cleanup(UUID streamSessionId) {
      cleanedIds.add(streamSessionId);
    }

    @Override
    public void reconcileUnbackedRuntimeAndStorage() {}
  }

  private static final class RecordingLifecycle
      extends UnsupportedStreamSessionLifecycleTransactions {

    private final boolean terminalizationFails;
    private final List<StreamSessionTermination> terminations = new ArrayList<>();
    private int terminalAttempts;

    private RecordingLifecycle(boolean terminalizationFails) {
      this.terminalizationFails = terminalizationFails;
    }

    @Override
    public boolean terminalize(StreamSessionTermination termination) {
      terminalAttempts++;
      if (terminalizationFails) {
        throw new IllegalStateException(
            "simulated serialization failure", new SQLException("test", "40001"));
      }
      terminations.add(termination);
      return true;
    }
  }
}
