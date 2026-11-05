package com.streamarr.server.fakes;

import com.streamarr.server.repositories.playbackreport.PlaybackReportRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.services.playbackreport.NewPlaybackReport;
import com.streamarr.server.services.playbackreport.PlaybackReport;
import com.streamarr.server.services.playbackreport.PlaybackReportSubmission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps shared playback reports in memory, ordered as PostgreSQL orders them. */
public final class FakePlaybackReportRepository implements PlaybackReportRepository {

  private final Map<UUID, PlaybackReport> reports = new ConcurrentHashMap<>();
  private final Map<UUID, String> profileNames = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> submissionIds = new ConcurrentHashMap<>();
  private final List<Boolean> deletionActors = new ArrayList<>();

  /** The name a stored report shows for the Profile, as the profile table holds it. */
  public void nameProfile(UUID profileId, String name) {
    profileNames.put(profileId, name);
  }

  /** For each deletion, whether it ran as the System actor. */
  public List<Boolean> deletionsRanAsSystemActor() {
    return List.copyOf(deletionActors);
  }

  public List<PlaybackReport> storedReports() {
    return List.copyOf(reports.values());
  }

  @Override
  public synchronized UUID save(NewPlaybackReport report) {
    var stored =
        reports.values().stream()
            .filter(
                candidate ->
                    candidate.playbackSessionId().equals(report.playbackSessionId())
                        && submissionIds.get(candidate.id()).equals(report.submissionId()))
            .findFirst();
    if (stored.isPresent()) {
      return stored.get().id();
    }

    var id = UUID.randomUUID();
    submissionIds.put(id, report.submissionId());
    reports.put(
        id,
        PlaybackReport.builder()
            .id(id)
            .sharedAt(report.sharedAt())
            .readAt(Optional.empty())
            .profileId(report.profileId())
            .profileName(profileNames.getOrDefault(report.profileId(), "Profile"))
            .playbackSessionId(report.playbackSessionId())
            .streamSessionId(report.streamSessionId())
            .mediaFileId(Optional.of(report.mediaFileId()))
            .note(report.note())
            .requestedChoice(report.requestedChoice())
            .playbackChoice(report.playbackChoice())
            .decisions(report.decisions())
            .player(report.player())
            .capabilityDeclaration(report.capabilityDeclaration())
            .build());
    return id;
  }

  @Override
  public Optional<UUID> findIdBySubmission(PlaybackReportSubmission submission) {
    return reports.values().stream()
        .filter(
            report ->
                report.profileId().equals(submission.profileId())
                    && report.playbackSessionId().equals(submission.playbackSessionId())
                    && submissionIds.get(report.id()).equals(submission.submissionId()))
        .map(PlaybackReport::id)
        .findFirst();
  }

  @Override
  public List<Row<PlaybackReport>> rowsBeyond(Seek seek) {
    return InMemoryKeyset.rowsBeyond(
        reports.values().stream()
            .map(
                report ->
                    new Row<>(
                        report,
                        new Position(new SortValue.OfInstant(report.sharedAt()), report.id())))
            .toList(),
        seek);
  }

  @Override
  public Optional<PlaybackReport> findById(UUID id) {
    return Optional.ofNullable(reports.get(id));
  }

  @Override
  public int countUnread() {
    return (int) reports.values().stream().filter(report -> report.readAt().isEmpty()).count();
  }

  @Override
  public Optional<PlaybackReport> markRead(UUID id, Instant readAt) {
    return Optional.ofNullable(
        reports.computeIfPresent(
            id,
            (_, report) -> {
              if (report.readAt().isPresent()) {
                return report;
              }

              return report.toBuilder().readAt(Optional.of(readAt)).build();
            }));
  }

  @Override
  public int deleteSharedBefore(Instant cutoff) {
    deletionActors.add(SystemWork.isRunningAsSystemActor());
    var expired =
        reports.values().stream().filter(report -> report.sharedAt().isBefore(cutoff)).toList();
    expired.forEach(report -> reports.remove(report.id()));
    return expired.size();
  }
}
