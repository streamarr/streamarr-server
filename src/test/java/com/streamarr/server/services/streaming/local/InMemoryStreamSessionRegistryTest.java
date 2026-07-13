package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.fixtures.StreamSessionFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class InMemoryStreamSessionRegistryTest {

  private InMemoryStreamSessionRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new InMemoryStreamSessionRegistry();
  }

  @Test
  @DisplayName("Should return session when saved by id")
  void shouldReturnSessionWhenSavedById() {
    var session = StreamSessionFixture.buildMpegtsSession();

    registry.save(session);

    var found = registry.findById(session.getSessionId());
    assertThat(found).isPresent().contains(session);
  }

  @Test
  @DisplayName("Should return empty when session not found")
  void shouldReturnEmptyWhenSessionNotFound() {
    var found = registry.findById(UUID.randomUUID());

    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("Should remove and return session when removed by id")
  void shouldRemoveAndReturnSessionWhenRemovedById() {
    var session = StreamSessionFixture.buildMpegtsSession();
    registry.save(session);

    var removed = registry.removeById(session.getSessionId());

    assertThat(removed).isPresent().contains(session);
    assertThat(registry.findById(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when removing nonexistent session")
  void shouldReturnEmptyWhenRemovingNonexistentSession() {
    var removed = registry.removeById(UUID.randomUUID());

    assertThat(removed).isEmpty();
  }

  @Test
  @DisplayName("Should return all saved sessions")
  void shouldReturnAllSavedSessions() {
    var session1 = StreamSessionFixture.buildMpegtsSession();
    var session2 = StreamSessionFixture.buildMpegtsSession();
    registry.save(session1);
    registry.save(session2);

    var all = registry.findAll();

    assertThat(all).containsExactlyInAnyOrder(session1, session2);
  }

  @Test
  @DisplayName("Should track count across saves and removes")
  void shouldTrackCountAcrossSavesAndRemoves() {
    var session1 = StreamSessionFixture.buildMpegtsSession();
    var session2 = StreamSessionFixture.buildMpegtsSession();

    assertThat(registry.count()).isZero();

    registry.save(session1);
    assertThat(registry.count()).isEqualTo(1);

    registry.save(session2);
    assertThat(registry.count()).isEqualTo(2);

    registry.removeById(session1.getSessionId());
    assertThat(registry.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should overwrite session when saving existing id")
  void shouldOverwriteSessionWhenSavingExistingId() {
    var session = StreamSessionFixture.buildMpegtsSession();
    registry.save(session);

    session.updatePlaybackState(300, PlaybackState.PLAYING);
    registry.save(session);

    assertThat(registry.count()).isEqualTo(1);
    var found = registry.findById(session.getSessionId());
    assertThat(found).isPresent();
    assertThat(found.get().getPlaybackSnapshot().positionSeconds()).isEqualTo(300);
  }
}
