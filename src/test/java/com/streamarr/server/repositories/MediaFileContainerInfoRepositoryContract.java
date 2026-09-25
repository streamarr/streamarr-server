package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbeAttemptFailure;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The probe rules that every {@link MediaFileContainerInfoRepository} keeps, so the fake that unit
 * tests use behaves as the PostgreSQL repository does.
 */
public interface MediaFileContainerInfoRepositoryContract {

  SourceFileSnapshot SNAPSHOT_A =
      new SourceFileSnapshot(1234, Instant.parse("2026-09-10T10:00:00.123456789Z"));
  SourceFileSnapshot SNAPSHOT_B =
      new SourceFileSnapshot(5678, Instant.parse("2026-09-11T10:00:00Z"));

  MediaFileContainerInfoRepository repository();

  /** Creates a media file the repository can hold probe results for. */
  UUID createMediaFile();

  void deleteMediaFile(UUID mediaFileId);

  /** Runs work that requires the caller's transaction. */
  <T> T inTransaction(Supplier<T> work);

  @Test
  @DisplayName("Should replace an older compatible outcome when publishing a newer version")
  default void shouldReplaceAnOlderCompatibleOutcomeWhenPublishingANewerVersion() {
    var mediaFileId = createMediaFile();
    repository().publish(publicationBuilder(mediaFileId).build());
    var replacement = success("hevc", "eac3");

    var published =
        repository()
            .publish(publicationBuilder(mediaFileId).probeVersion(2).outcome(replacement).build());

    assertThat(published).isTrue();
    assertThat(stored(mediaFileId))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.getProbeVersion()).isEqualTo(2);
              assertThat(stored.getOutcome()).isEqualTo(replacement);
            });
  }

  @Test
  @DisplayName("Should keep a newer outcome when an older version publishes the same snapshot")
  default void shouldKeepANewerOutcomeWhenAnOlderVersionPublishesTheSameSnapshot() {
    var mediaFileId = createMediaFile();
    var newer = success("hevc", "eac3");
    repository().publish(publicationBuilder(mediaFileId).probeVersion(2).outcome(newer).build());

    var published = repository().publish(publicationBuilder(mediaFileId).build());

    assertThat(published).isFalse();
    assertThat(stored(mediaFileId))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.getProbeVersion()).isEqualTo(2);
              assertThat(stored.getOutcome()).isEqualTo(newer);
            });
  }

  @Test
  @DisplayName("Should replace the outcome when the source snapshot differs from a newer version")
  default void shouldReplaceTheOutcomeWhenTheSourceSnapshotDiffersFromANewerVersion() {
    var mediaFileId = createMediaFile();
    repository()
        .publish(
            publicationBuilder(mediaFileId)
                .probeVersion(2)
                .outcome(success("hevc", "eac3"))
                .build());
    var replacement = success("h264", "aac");

    var published =
        repository()
            .publish(
                publicationBuilder(mediaFileId).snapshot(SNAPSHOT_B).outcome(replacement).build());

    assertThat(published).isTrue();
    assertThat(stored(mediaFileId))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.getSnapshot()).isEqualTo(SNAPSHOT_B);
              assertThat(stored.getProbeVersion()).isEqualTo(1);
              assertThat(stored.getOutcome()).isEqualTo(replacement);
            });
  }

  @Test
  @DisplayName("Should reject publication when the media file no longer exists")
  default void shouldRejectPublicationWhenTheMediaFileNoLongerExists() {
    var mediaFileId = createMediaFile();
    deleteMediaFile(mediaFileId);

    var published = repository().publish(publicationBuilder(mediaFileId).build());

    assertThat(published).isFalse();
    assertThat(stored(mediaFileId)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(InputChange.class)
  @DisplayName("Should reject obsolete publication when the requested inputs changed")
  default void shouldRejectObsoletePublicationWhenTheRequestedInputsChanged(InputChange change) {
    var mediaFileId = createMediaFile();
    var desired =
        switch (change) {
          case SNAPSHOT -> new ProbeInputs(SNAPSHOT_B, 1);
          case VERSION -> new ProbeInputs(SNAPSHOT_A, 2);
        };
    repository().trySaveProbeRequest(mediaFileId, desired);

    var published = repository().publish(publicationBuilder(mediaFileId).build());

    assertThat(published).isFalse();
    assertThat(stored(mediaFileId)).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("compatibleOutcomes")
  @DisplayName("Should retain a compatible outcome when only the requested probe version changed")
  default void shouldRetainACompatibleOutcomeWhenOnlyTheRequestedProbeVersionChanged(
      ProbeOutcome outcome) {
    var mediaFileId = createMediaFile();
    repository().publish(publicationBuilder(mediaFileId).outcome(outcome).build());

    repository().trySaveProbeRequest(mediaFileId, new ProbeInputs(SNAPSHOT_A, 2));

    assertThat(stored(mediaFileId))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.getSnapshot()).isEqualTo(SNAPSHOT_A);
              assertThat(stored.getProbeVersion()).isEqualTo(1);
              assertThat(stored.getOutcome()).isEqualTo(outcome);
            });
  }

  @Test
  @DisplayName("Should delete the stored outcome when a request carries another snapshot")
  default void shouldDeleteTheStoredOutcomeWhenARequestCarriesAnotherSnapshot() {
    var mediaFileId = createMediaFile();
    repository().publish(publicationBuilder(mediaFileId).build());

    repository().trySaveProbeRequest(mediaFileId, new ProbeInputs(SNAPSHOT_B, 1));

    assertThat(stored(mediaFileId)).isEmpty();
  }

  @Test
  @DisplayName("Should keep the stored outcome when a request carries the same snapshot")
  default void shouldKeepTheStoredOutcomeWhenARequestCarriesTheSameSnapshot() {
    var mediaFileId = createMediaFile();
    repository().publish(publicationBuilder(mediaFileId).build());

    repository().trySaveProbeRequest(mediaFileId, new ProbeInputs(SNAPSHOT_A, 1));

    assertThat(stored(mediaFileId))
        .hasValueSatisfying(
            stored -> assertThat(stored.getOutcome()).isEqualTo(success("h264", "aac")));
  }

  @Test
  @DisplayName("Should reject a probe request when the media file no longer exists")
  default void shouldRejectAProbeRequestWhenTheMediaFileNoLongerExists() {
    var mediaFileId = createMediaFile();
    deleteMediaFile(mediaFileId);

    var saved = repository().trySaveProbeRequest(mediaFileId, new ProbeInputs(SNAPSHOT_A, 1));

    assertThat(saved).isFalse();
    assertThat(repository().findProbeStates(List.of(mediaFileId))).isEmpty();
  }

  @Test
  @DisplayName("Should clear the saved failure when an outcome is stored for the same inputs")
  default void shouldClearTheSavedFailureWhenAnOutcomeIsStoredForTheSameInputs() {
    var mediaFileId = createMediaFile();
    var inputs = new ProbeInputs(SNAPSHOT_A, 1);
    repository().trySaveProbeRequest(mediaFileId, inputs);
    repository().trySaveProbeFailure(mediaFileId, inputs, failure());

    repository().publish(publicationBuilder(mediaFileId).build());

    assertThat(stateOf(mediaFileId).failure()).isEmpty();
    assertThat(stateOf(mediaFileId).stored())
        .hasValueSatisfying(stored -> assertThat(stored.inputs()).isEqualTo(inputs));
  }

  @Test
  @DisplayName("Should keep the saved failure when the same inputs are requested again")
  default void shouldKeepTheSavedFailureWhenTheSameInputsAreRequestedAgain() {
    var mediaFileId = createMediaFile();
    var inputs = new ProbeInputs(SNAPSHOT_A, 1);
    repository().trySaveProbeRequest(mediaFileId, inputs);
    repository().trySaveProbeFailure(mediaFileId, inputs, failure());

    repository().trySaveProbeRequest(mediaFileId, inputs);

    assertThat(stateOf(mediaFileId).failure()).contains(failure());
  }

  @Test
  @DisplayName("Should clear the saved failure when a request carries different inputs")
  default void shouldClearTheSavedFailureWhenARequestCarriesDifferentInputs() {
    var mediaFileId = createMediaFile();
    var inputs = new ProbeInputs(SNAPSHOT_A, 1);
    repository().trySaveProbeRequest(mediaFileId, inputs);
    repository().trySaveProbeFailure(mediaFileId, inputs, failure());
    var changed = new ProbeInputs(SNAPSHOT_B, 1);

    repository().trySaveProbeRequest(mediaFileId, changed);

    assertThat(stateOf(mediaFileId).requested()).contains(changed);
    assertThat(stateOf(mediaFileId).failure()).isEmpty();
  }

  @Test
  @DisplayName("Should not save a failure when the attempted inputs are no longer requested")
  default void shouldNotSaveAFailureWhenTheAttemptedInputsAreNoLongerRequested() {
    var mediaFileId = createMediaFile();
    repository().trySaveProbeRequest(mediaFileId, new ProbeInputs(SNAPSHOT_B, 1));

    var saved =
        repository().trySaveProbeFailure(mediaFileId, new ProbeInputs(SNAPSHOT_A, 1), failure());

    assertThat(saved).isFalse();
    assertThat(stateOf(mediaFileId).failure()).isEmpty();
  }

  @Test
  @DisplayName("Should not save a failure when the media file no longer exists")
  default void shouldNotSaveAFailureWhenTheMediaFileNoLongerExists() {
    var mediaFileId = createMediaFile();
    var inputs = new ProbeInputs(SNAPSHOT_A, 1);
    repository().trySaveProbeRequest(mediaFileId, inputs);
    deleteMediaFile(mediaFileId);

    var saved = repository().trySaveProbeFailure(mediaFileId, inputs, failure());

    assertThat(saved).isFalse();
  }

  @Test
  @DisplayName("Should hold no probe state when the media file has no probe yet")
  default void shouldHoldNoProbeStateWhenTheMediaFileHasNoProbeYet() {
    var mediaFileId = createMediaFile();

    assertThat(repository().findProbeStates(List.of(mediaFileId)))
        .containsExactly(
            ProbeState.builder()
                .mediaFileId(mediaFileId)
                .requested(Optional.empty())
                .stored(Optional.empty())
                .failure(Optional.empty())
                .build());
  }

  @Test
  @DisplayName("Should withdraw the requested inputs and their failure when the source is gone")
  default void shouldWithdrawTheRequestedInputsAndTheirFailureWhenTheSourceIsGone() {
    var mediaFileId = createMediaFile();
    var inputs = new ProbeInputs(SNAPSHOT_A, 1);
    repository().trySaveProbeRequest(mediaFileId, inputs);
    repository().trySaveProbeFailure(mediaFileId, inputs, failure());

    inTransaction(
        () -> {
          repository().withdrawProbeRequest(mediaFileId);
          return null;
        });

    assertThat(stateOf(mediaFileId).requested()).isEmpty();
    assertThat(stateOf(mediaFileId).failure()).isEmpty();
  }

  @Test
  @DisplayName("Should lock the requested inputs when a probe is requested")
  default void shouldLockTheRequestedInputsWhenAProbeIsRequested() {
    var mediaFileId = createMediaFile();
    var inputs = new ProbeInputs(SNAPSHOT_A, 1);
    repository().trySaveProbeRequest(mediaFileId, inputs);

    assertThat(inTransaction(() -> repository().lockProbeInputs(mediaFileId))).contains(inputs);
  }

  @Test
  @DisplayName("Should lock no inputs when the media file no longer exists")
  default void shouldLockNoInputsWhenTheMediaFileNoLongerExists() {
    var mediaFileId = createMediaFile();
    repository().trySaveProbeRequest(mediaFileId, new ProbeInputs(SNAPSHOT_A, 1));
    deleteMediaFile(mediaFileId);

    assertThat(inTransaction(() -> repository().lockProbeInputs(mediaFileId))).isEmpty();
  }

  enum InputChange {
    SNAPSHOT,
    VERSION
  }

  static Stream<ProbeOutcome> compatibleOutcomes() {
    return Stream.of(
        success("h264", "aac"),
        new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA),
        new ProbeOutcome.Failure(ProbeError.NO_VIDEO_STREAM));
  }

  private Optional<MediaFileContainerInfo> stored(UUID mediaFileId) {
    return repository().findByMediaFileId(mediaFileId);
  }

  private ProbeState stateOf(UUID mediaFileId) {
    return repository().findProbeStates(List.of(mediaFileId)).getFirst();
  }

  /** A version 1 publication of a successful outcome for {@link #SNAPSHOT_A}. */
  private static ProbePublication.ProbePublicationBuilder publicationBuilder(UUID mediaFileId) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(SNAPSHOT_A)
        .probeVersion(1)
        .outcome(success("h264", "aac"));
  }

  private static ProbeAttemptFailure failure() {
    return ProbeAttemptFailure.builder()
        .reason(ItemFailureReason.SOURCE_INACCESSIBLE)
        .detail("Worker could not read the source")
        .failedAt(Instant.parse("2026-09-12T10:00:00Z"))
        .build();
  }

  private static ProbeOutcome.Success success(String videoCodec, String audioCodec) {
    return new ProbeOutcome.Success(
        ProbeContainer.builder()
            .format(Optional.of("matroska,webm"))
            .duration(Optional.of(Duration.ofSeconds(123, 456)))
            .bitrate(OptionalLong.of(5_000_000))
            .build(),
        List.of(
            StreamInfo.builder()
                .index(0)
                .codecType("video")
                .codec(Optional.of(videoCodec))
                .width(OptionalInt.of(1920))
                .height(OptionalInt.of(1080))
                .build(),
            StreamInfo.builder()
                .index(1)
                .codecType("audio")
                .codec(Optional.of(audioCodec))
                .build()));
  }
}
