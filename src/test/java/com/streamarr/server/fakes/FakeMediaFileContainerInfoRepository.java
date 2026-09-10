package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.MediaFileStreamInfo;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.PersistedProbeFixture;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class FakeMediaFileContainerInfoRepository implements MediaFileContainerInfoRepository {

  private final Map<UUID, MediaFileContainerInfo> rows = new ConcurrentHashMap<>();
  private final List<ProbePublication> publications = new ArrayList<>();
  private Optional<ProbeOutcome.Success> defaultProbe = Optional.empty();
  private Predicate<UUID> mediaFileExists = _ -> true;

  /** Answers every media file id with this probe unless a row was stored for it. */
  public void setDefaultProbe(MediaProbe probe) {
    defaultProbe = Optional.of(ProbeFixture.completeProbe(probe));
  }

  public void clear() {
    defaultProbe = Optional.empty();
    rows.clear();
  }

  @Override
  public Optional<MediaFileContainerInfo> findByMediaFileId(UUID mediaFileId) {
    return Optional.ofNullable(rows.get(mediaFileId))
        .or(
            () ->
                defaultProbe.map(
                    probe -> PersistedProbeFixture.storedProbeBuilder(mediaFileId, probe).build()));
  }

  @Override
  public boolean publish(ProbePublication publication) {
    if (!mediaFileExists.test(publication.mediaFileId())) {
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
    return true;
  }

  @Override
  public void invalidateOutcomeUnlessSnapshotMatches(
      UUID mediaFileId, SourceFileSnapshot snapshot) {
    Optional.ofNullable(rows.get(mediaFileId))
        .filter(row -> !row.getSnapshot().equals(snapshot))
        .ifPresent(_ -> rows.remove(mediaFileId));
  }

  public void store(MediaFileContainerInfo row) {
    rows.put(row.getMediaFileId(), row);
  }

  public void mediaFileExistsWhen(Predicate<UUID> predicate) {
    this.mediaFileExists = predicate;
  }

  public List<ProbePublication> publications() {
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
