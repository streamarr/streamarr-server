package com.streamarr.server.services.playbackreport;

import static com.streamarr.server.fixtures.PlayerFiguresFixture.unknownFiguresBuilder;
import static com.streamarr.server.jooq.generated.tables.PlaybackReport.PLAYBACK_REPORT;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.playbackreport.PlaybackReportRepository;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Shared playback report paging")
class PlaybackReportPagedListIT
    extends PagedListContract<PlaybackReport, PlaybackReportPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);
  // Future share times: the retention sweep, which can fire during the suite, never deletes them.
  private static final Instant EARLIEST_WINDOW = Instant.parse("2100-01-01T00:00:00Z");

  @Autowired private PlaybackReportService playbackReports;
  @Autowired private PlaybackReportRepository repository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private AuthTestSupport auth;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;

  /**
   * A ServerAdmin, the viewer who shares the reports, and a time no other test's reports share:
   * rank 0 was shared then, and each higher rank one second earlier.
   */
  record Scope(
      AuthTestSupport.TestIdentity admin,
      AuthTestSupport.TestIdentity viewer,
      UUID mediaFileId,
      Instant newestSharedAt) {}

  @Override
  protected Scope createScope() {
    var newest =
        EARLIEST_WINDOW.plus(
            ThreadLocalRandom.current().nextLong(1, 10L * 365 * 24 * 60 * 60), ChronoUnit.SECONDS);
    return new Scope(
        auth.createAdminIdentity(), auth.createIdentity(), createMediaFile().getId(), newest);
  }

  // Deleting the viewer's Profile deletes its reports.
  @Override
  protected void deleteScope(Scope scope) {
    auth.deleteIdentity(scope.viewer());
    auth.deleteIdentity(scope.admin());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    return transactionTemplate.execute(
        _ ->
            repository.save(
                NewPlaybackReport.builder()
                    .sharedAt(scope.newestSharedAt().minusSeconds(rank))
                    .profileId(scope.viewer().profile().getId())
                    .playbackSessionId(UUID.randomUUID())
                    .streamSessionId(UUID.randomUUID())
                    .mediaFileId(scope.mediaFileId())
                    .note(Optional.empty())
                    .requestedChoice(VideoQuality.AUTO)
                    .playbackChoice(VideoQuality.AUTO)
                    .decisions(List.of())
                    .player(unknownFiguresBuilder().build())
                    .build()));
  }

  // The retention sweep removes a report from the list.
  @Override
  protected void remove(Scope scope, UUID item) {
    dsl.deleteFrom(PLAYBACK_REPORT).where(PLAYBACK_REPORT.ID.eq(item)).execute();
  }

  @Override
  protected ListPage<PlaybackReport> page(Scope scope, PageRequest request) {
    return playbackReports.playbackReports(auth.identityOf(scope.admin()), request);
  }

  @Override
  protected UUID idOf(PlaybackReport report) {
    return report.id();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.DESC;
  }

  @Override
  protected boolean sortValuesCanTie() {
    return true;
  }

  @Override
  protected Optional<Position> positionBeforeArrangedItems(Scope scope) {
    return Optional.of(
        new Position(new SortValue.OfInstant(scope.newestSharedAt().plusSeconds(1)), LOWEST_ID));
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.of(
        new Position(new SortValue.OfInstant(scope.newestSharedAt().minusSeconds(10)), LOWEST_ID));
  }

  private MediaFile createMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("paged-report.mkv")
            .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
            .size(1234)
            .build());
  }
}
