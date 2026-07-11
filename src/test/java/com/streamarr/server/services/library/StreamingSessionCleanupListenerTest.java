package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.streamarr.server.domain.streaming.StreamSessionTerminalReason;
import com.streamarr.server.repositories.streaming.MediaStreamTermination;
import com.streamarr.server.services.library.events.LibraryRemovedEvent;
import com.streamarr.server.services.streaming.UnsupportedStreamSessionLifecycleTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Streaming Session Cleanup Listener Tests")
class StreamingSessionCleanupListenerTest {

  private static final Instant NOW = Instant.parse("2026-07-10T20:00:00Z");

  private FakeLifecycleTransactions lifecycleTransactions;
  private StreamingSessionCleanupListener listener;

  @BeforeEach
  void setUp() {
    lifecycleTransactions = new FakeLifecycleTransactions();
    listener =
        new StreamingSessionCleanupListener(
            lifecycleTransactions, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("Should durably terminalize matching streams and defer runtime cleanup")
  void shouldDurablyTerminalizeMatchingStreamsAndDeferRuntimeCleanup() {
    var mediaFileId = UUID.randomUUID();
    var streamSessionId = UUID.randomUUID();
    lifecycleTransactions.affectedStreamIds = List.of(streamSessionId);

    listener.onLibraryRemoved(new LibraryRemovedEvent("/library/path", Set.of(mediaFileId)));

    assertThat(lifecycleTransactions.termination.mediaFileIds()).containsExactly(mediaFileId);
    assertThat(lifecycleTransactions.termination.reason())
        .isEqualTo(StreamSessionTerminalReason.SOURCE_DELETED);
    assertThat(lifecycleTransactions.termination.terminalAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("Should skip durable and runtime cleanup when event has no media files")
  void shouldSkipCleanupWhenEventHasNoMediaFiles() {
    listener.onLibraryRemoved(new LibraryRemovedEvent("/library/path", Set.of()));

    assertThat(lifecycleTransactions.termination).isNull();
  }

  @Test
  @DisplayName("Should isolate a durable terminalization failure")
  void shouldIsolateDurableTerminalizationFailure() {
    var mediaFileId = UUID.randomUUID();
    lifecycleTransactions.failTerminalization();

    assertThatNoException()
        .isThrownBy(
            () ->
                listener.onLibraryRemoved(
                    new LibraryRemovedEvent("/library/path", Set.of(mediaFileId))));

    assertThat(lifecycleTransactions.termination.mediaFileIds()).containsExactly(mediaFileId);
    assertThat(lifecycleTransactions.termination.reason())
        .isEqualTo(StreamSessionTerminalReason.SOURCE_DELETED);
  }

  private static final class FakeLifecycleTransactions
      extends UnsupportedStreamSessionLifecycleTransactions {

    private MediaStreamTermination termination;
    private List<UUID> affectedStreamIds = List.of();
    private boolean terminalizationFails;

    private void failTerminalization() {
      terminalizationFails = true;
    }

    @Override
    public List<UUID> terminalizeByMediaFiles(MediaStreamTermination requestedTermination) {
      termination = requestedTermination;
      if (terminalizationFails) {
        throw new IllegalStateException("simulated terminalization failure");
      }
      return affectedStreamIds;
    }
  }
}
