package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that every {@link LearnedStreamEndRepository} keeps, so the fake that unit tests use
 * behaves as the PostgreSQL repository does.
 */
public interface LearnedStreamEndRepositoryContract {

  SourceFileSnapshot SNAPSHOT =
      new SourceFileSnapshot(4_096, Instant.parse("2026-11-06T09:00:00.123456789Z"));
  SourceFileSnapshot CHANGED_SNAPSHOT =
      new SourceFileSnapshot(8_192, Instant.parse("2026-11-07T09:00:00Z"));

  LearnedStreamEndRepository repository();

  /** Creates a media file the repository can hold learned end times for. */
  UUID createMediaFile();

  @Test
  @DisplayName("Should read a learned end time when it was learned for the snapshot")
  default void shouldReadALearnedEndTimeWhenItWasLearnedForTheSnapshot() {
    var mediaFileId = createMediaFile();
    var learned = learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84, 21_333_333));

    var stored = repository().store(learned);

    assertThat(stored).isTrue();
    assertThat(repository().findFor(mediaFileId, SNAPSHOT)).containsExactly(learned);
  }

  @Test
  @DisplayName("Should keep the first learned end time when a later attempt learns another one")
  default void shouldKeepTheFirstLearnedEndTimeWhenALaterAttemptLearnsAnotherOne() {
    var mediaFileId = createMediaFile();
    var first = learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84));
    repository().store(first);

    var stored = repository().store(learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(90)));

    assertThat(stored).isFalse();
    assertThat(repository().findFor(mediaFileId, SNAPSHOT)).containsExactly(first);
  }

  @Test
  @DisplayName("Should replace a learned end time when the file changed since it was learned")
  default void shouldReplaceALearnedEndTimeWhenTheFileChangedSinceItWasLearned() {
    var mediaFileId = createMediaFile();
    repository().store(learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84)));
    var relearned = learned(mediaFileId, CHANGED_SNAPSHOT, Duration.ofSeconds(90));

    var stored = repository().store(relearned);

    assertThat(stored).isTrue();
    assertThat(repository().findFor(mediaFileId, CHANGED_SNAPSHOT)).containsExactly(relearned);
    assertThat(repository().findFor(mediaFileId, SNAPSHOT)).isEmpty();
  }

  @Test
  @DisplayName("Should read only the file's own learned end times")
  default void shouldReadOnlyTheFilesOwnLearnedEndTimes() {
    var mediaFileId = createMediaFile();
    var otherFileId = createMediaFile();
    var learned = learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(84));
    var otherStream =
        learned(mediaFileId, SNAPSHOT, Duration.ofSeconds(64)).toBuilder().streamIndex(0).build();
    repository().store(learned);
    repository().store(otherStream);
    repository().store(learned(otherFileId, SNAPSHOT, Duration.ofSeconds(12)));

    assertThat(repository().findFor(mediaFileId, SNAPSHOT))
        .containsExactlyInAnyOrder(learned, otherStream);
  }

  private static LearnedStreamEnd learned(
      UUID mediaFileId, SourceFileSnapshot snapshot, Duration endTime) {
    return LearnedStreamEnd.builder()
        .mediaFileId(mediaFileId)
        .snapshot(snapshot)
        .streamIndex(1)
        .endTime(endTime)
        .build();
  }
}
