package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.StreamSessionFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("In-Memory Stream Session Registry Tests")
class InMemoryStreamSessionRegistryTest {

  private InMemoryStreamSessionRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new InMemoryStreamSessionRegistry();
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
  @DisplayName("Should track count when sessions are saved and removed")
  void shouldTrackCountWhenSessionsAreSavedAndRemoved() {
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
}
