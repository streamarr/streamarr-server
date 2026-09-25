package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.MediaFileStreamInfo;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.ProbeAttemptFailure;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.fixtures.PersistedProbeFixture;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FakeMediaFileContainerInfoRepository implements MediaFileContainerInfoRepository {

  private final Map<UUID, MediaFileContainerInfo> rows = new ConcurrentHashMap<>();
  private final Map<UUID, ProbeInputs> desiredInputs = new ConcurrentHashMap<>();
  private final Map<UUID, ProbeAttemptFailure> failures = new ConcurrentHashMap<>();
  private final List<ProbePublication> publications = new ArrayList<>();
  private Optional<ProbeOutcome.Success> defaultProbe = Optional.empty();
  private final Set<UUID> deletedMediaFiles = ConcurrentHashMap.newKeySet();
  private final MediaFileRowLocks rowLocks = new MediaFileRowLocks();

  /** Answers every media file id with this probe unless a row was stored for it. */
  public void setDefaultProbe(MediaProbe probe) {
    setDefaultOutcome(ProbeFixture.completeProbe(probe));
  }

  public void setDefaultOutcome(ProbeOutcome.Success outcome) {
    defaultProbe = Optional.of(outcome);
  }

  public void clear() {
    defaultProbe = Optional.empty();
    rows.clear();
    desiredInputs.clear();
    failures.clear();
  }

  @Override
  public Optional<MediaFileContainerInfo> findByMediaFileId(UUID mediaFileId) {
    if (isDeleted(mediaFileId)) {
      return Optional.empty();
    }

    return Optional.ofNullable(rows.get(mediaFileId))
        .or(
            () ->
                defaultProbe.map(
                    probe -> PersistedProbeFixture.storedProbeBuilder(mediaFileId, probe).build()));
  }

  @Override
  public boolean publish(ProbePublication publication) {
    return rowLocks.holding(publication.mediaFileId(), () -> publishHoldingRowLock(publication));
  }

  private synchronized boolean publishHoldingRowLock(ProbePublication publication) {
    if (isDeleted(publication.mediaFileId())) {
      return false;
    }

    if (Optional.ofNullable(desiredInputs.get(publication.mediaFileId()))
        .filter(
            inputs ->
                !inputs.equals(new ProbeInputs(publication.snapshot(), publication.probeVersion())))
        .isPresent()) {
      return false;
    }

    var existing = Optional.ofNullable(rows.get(publication.mediaFileId()));
    if (existing
        .filter(row -> row.getSnapshot().equals(publication.snapshot()))
        .filter(row -> row.getProbeVersion() > publication.probeVersion())
        .isPresent()) {
      return false;
    }

    publications.add(publication);
    rows.put(publication.mediaFileId(), toRow(publication));
    failures.remove(publication.mediaFileId());
    return true;
  }

  @Override
  public boolean trySaveProbeRequest(UUID mediaFileId, ProbeInputs inputs) {
    return rowLocks.holding(
        mediaFileId, () -> trySaveProbeRequestHoldingRowLock(mediaFileId, inputs));
  }

  private synchronized boolean trySaveProbeRequestHoldingRowLock(
      UUID mediaFileId, ProbeInputs inputs) {
    if (isDeleted(mediaFileId)) {
      return false;
    }

    if (!inputs.equals(desiredInputs.put(mediaFileId, inputs))) {
      failures.remove(mediaFileId);
    }

    invalidateOutcomeUnlessSnapshotMatches(mediaFileId, inputs.snapshot());
    return true;
  }

  @Override
  public boolean trySaveProbeFailure(
      UUID mediaFileId, ProbeInputs inputs, ProbeAttemptFailure failure) {
    return rowLocks.holding(
        mediaFileId, () -> trySaveProbeFailureHoldingRowLock(mediaFileId, inputs, failure));
  }

  private synchronized boolean trySaveProbeFailureHoldingRowLock(
      UUID mediaFileId, ProbeInputs inputs, ProbeAttemptFailure failure) {
    if (isDeleted(mediaFileId)) {
      return false;
    }

    if (!inputs.equals(desiredInputs.get(mediaFileId))) {
      return false;
    }

    failures.put(mediaFileId, failure);
    return true;
  }

  @Override
  public synchronized List<ProbeState> findProbeStates(Collection<UUID> mediaFileIds) {
    return mediaFileIds.stream()
        .filter(mediaFileId -> !isDeleted(mediaFileId))
        .map(
            mediaFileId ->
                ProbeState.builder()
                    .mediaFileId(mediaFileId)
                    .requested(Optional.ofNullable(desiredInputs.get(mediaFileId)))
                    .stored(Optional.ofNullable(rows.get(mediaFileId)).map(this::stored))
                    .failure(Optional.ofNullable(failures.get(mediaFileId)))
                    .build())
        .toList();
  }

  private boolean isDeleted(UUID mediaFileId) {
    return deletedMediaFiles.contains(mediaFileId);
  }

  private ProbeState.Stored stored(MediaFileContainerInfo row) {
    return new ProbeState.Stored(
        new ProbeInputs(row.getSnapshot(), row.getProbeVersion()), row.getProbeError());
  }

  @Override
  public void withdrawProbeRequest(UUID mediaFileId) {
    rowLocks.holding(
        mediaFileId,
        () -> {
          synchronized (this) {
            desiredInputs.remove(mediaFileId);
            failures.remove(mediaFileId);
          }

          return null;
        });
  }

  @Override
  public Optional<ProbeInputs> lockProbeInputs(UUID mediaFileId) {
    return rowLocks.holding(mediaFileId, () -> requestedInputs(mediaFileId));
  }

  private synchronized Optional<ProbeInputs> requestedInputs(UUID mediaFileId) {
    return Optional.ofNullable(desiredInputs.get(mediaFileId));
  }

  private void invalidateOutcomeUnlessSnapshotMatches(
      UUID mediaFileId, SourceFileSnapshot snapshot) {
    Optional.ofNullable(rows.get(mediaFileId))
        .filter(row -> !row.getSnapshot().equals(snapshot))
        .ifPresent(_ -> rows.remove(mediaFileId));
  }

  public void store(MediaFileContainerInfo row) {
    rows.put(row.getMediaFileId(), row);
  }

  /** Waits until {@code count} writes for the media file wait for another's open writes. */
  public void awaitBlockedWrites(UUID mediaFileId, int count, Duration bound)
      throws InterruptedException {
    rowLocks.awaitWaiting(mediaFileId, count, bound);
  }

  /**
   * Runs writes for one media file as one transaction: other writers for the file wait until they
   * finish, and the file's probe state is restored when they throw.
   */
  public void rollBackOnFailure(UUID mediaFileId, Runnable writes) {
    rowLocks.holding(
        mediaFileId,
        () -> {
          runRollingBack(mediaFileId, writes);
          return null;
        });
  }

  private void runRollingBack(UUID mediaFileId, Runnable writes) {
    Optional<ProbeInputs> requested;
    Optional<ProbeAttemptFailure> failure;
    Optional<MediaFileContainerInfo> row;
    synchronized (this) {
      requested = Optional.ofNullable(desiredInputs.get(mediaFileId));
      failure = Optional.ofNullable(failures.get(mediaFileId));
      row = Optional.ofNullable(rows.get(mediaFileId));
    }

    try {
      writes.run();
    } catch (RuntimeException rejected) {
      synchronized (this) {
        restore(desiredInputs, mediaFileId, requested);
        restore(failures, mediaFileId, failure);
        restore(rows, mediaFileId, row);
      }

      throw rejected;
    }
  }

  private static <T> void restore(Map<UUID, T> values, UUID mediaFileId, Optional<T> value) {
    value.ifPresentOrElse(
        earlier -> values.put(mediaFileId, earlier), () -> values.remove(mediaFileId));
  }

  /**
   * Deletes the media file as the database would: its outcome, requested inputs and failure go with
   * it, and later writes for it are rejected.
   */
  public void deleteMediaFile(UUID mediaFileId) {
    rowLocks.holding(
        mediaFileId,
        () -> {
          synchronized (this) {
            deletedMediaFiles.add(mediaFileId);
            rows.remove(mediaFileId);
            desiredInputs.remove(mediaFileId);
            failures.remove(mediaFileId);
          }

          return null;
        });
  }

  public synchronized List<ProbePublication> publications() {
    return List.copyOf(publications);
  }

  private static MediaFileContainerInfo toRow(ProbePublication publication) {
    var builder =
        MediaFileContainerInfo.builder()
            .mediaFileId(publication.mediaFileId())
            .snapshot(publication.snapshot())
            .probeVersion(publication.probeVersion());
    return switch (publication.outcome()) {
      case ProbeOutcome.Failure(var error) -> builder.probeError(error).build();
      case ProbeOutcome.Success(var container, var streams) ->
          builder
              .container(container)
              .streams(
                  streams.stream()
                      .map(
                          stream ->
                              MediaFileStreamInfo.builder().stream(
                                      publication.mediaFileId(), stream)
                                  .build())
                      .toList())
              .build();
    };
  }
}
