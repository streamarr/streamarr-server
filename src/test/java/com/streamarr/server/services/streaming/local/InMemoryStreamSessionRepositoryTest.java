package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.StreamSessionFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class InMemoryStreamSessionRepositoryTest {

  private InMemoryStreamSessionRepository repository;

  @BeforeEach
  void setUp() {
    repository = new InMemoryStreamSessionRepository();
  }

  @Test
  @DisplayName("Should return session when saved by id")
  void shouldReturnSessionWhenSavedById() {
    var session = StreamSessionFixture.buildMpegtsSession();

    repository.save(session);

    var found = repository.findById(session.getSessionId());
    assertThat(found).isPresent().contains(session);
  }

  @Test
  @DisplayName("Should return empty when session not found")
  void shouldReturnEmptyWhenSessionNotFound() {
    var found = repository.findById(UUID.randomUUID());

    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("Should remove and return session when removed by id")
  void shouldRemoveAndReturnSessionWhenRemovedById() {
    var session = StreamSessionFixture.buildMpegtsSession();
    repository.save(session);

    var removed = repository.removeById(session.getSessionId());

    assertThat(removed).isPresent().contains(session);
    assertThat(repository.findById(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when removing nonexistent session")
  void shouldReturnEmptyWhenRemovingNonexistentSession() {
    var removed = repository.removeById(UUID.randomUUID());

    assertThat(removed).isEmpty();
  }

  @Test
  @DisplayName("Should return all saved sessions")
  void shouldReturnAllSavedSessions() {
    var session1 = StreamSessionFixture.buildMpegtsSession();
    var session2 = StreamSessionFixture.buildMpegtsSession();
    repository.save(session1);
    repository.save(session2);

    var all = repository.findAll();

    assertThat(all).containsExactlyInAnyOrder(session1, session2);
  }

  @Test
  @DisplayName("Should track count across saves and removes")
  void shouldTrackCountAcrossSavesAndRemoves() {
    var session1 = StreamSessionFixture.buildMpegtsSession();
    var session2 = StreamSessionFixture.buildMpegtsSession();

    assertThat(repository.count()).isZero();

    repository.save(session1);
    assertThat(repository.count()).isEqualTo(1);

    repository.save(session2);
    assertThat(repository.count()).isEqualTo(2);

    repository.removeById(session1.getSessionId());
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should overwrite session when saving existing id")
  void shouldOverwriteSessionWhenSavingExistingId() {
    var session = StreamSessionFixture.buildMpegtsSession();
    repository.save(session);

    session.setSeekPosition(300);
    repository.save(session);

    assertThat(repository.count()).isEqualTo(1);
    var found = repository.findById(session.getSessionId());
    assertThat(found).isPresent();
    assertThat(found.get().getSeekPosition()).isEqualTo(300);
  }
}
