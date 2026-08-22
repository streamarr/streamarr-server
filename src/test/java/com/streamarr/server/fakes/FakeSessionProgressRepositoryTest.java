package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.streaming.SessionProgress;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Fake Session Progress Repository Tests")
class FakeSessionProgressRepositoryTest {

  private final FakeSessionProgressRepository repository = new FakeSessionProgressRepository();

  @Test
  @DisplayName("Should return Profile activity newest first when modification times differ")
  void shouldReturnProfileActivityNewestFirstWhenModificationTimesDiffer() {
    var profileId = UUID.randomUUID();
    repository.save(progress(profileId));
    repository.save(progress(profileId));
    var mapOrder = repository.findByProfileIdOrderByLastModifiedOnDesc(profileId);
    AuditFieldSetter.setLastModifiedOn(mapOrder.getFirst(), Instant.EPOCH);
    AuditFieldSetter.setLastModifiedOn(mapOrder.getLast(), Instant.EPOCH.plusSeconds(1));

    assertThat(repository.findByProfileIdOrderByLastModifiedOnDesc(profileId))
        .extracting(SessionProgress::getId)
        .containsExactly(mapOrder.getLast().getId(), mapOrder.getFirst().getId());
  }

  private static SessionProgress progress(UUID profileId) {
    return SessionProgress.builder()
        .sessionId(UUID.randomUUID())
        .profileId(profileId)
        .mediaFileId(UUID.randomUUID())
        .durationSeconds(600)
        .build();
  }
}
