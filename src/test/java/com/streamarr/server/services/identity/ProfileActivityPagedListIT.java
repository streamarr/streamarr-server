package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.MediaEntityFixture;
import com.streamarr.server.fixtures.SessionProgressFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Profile activity paging")
class ProfileActivityPagedListIT
    extends PagedListContract<SessionProgress, ProfileActivityPagedListIT.Scope> {

  private static final Instant NEWEST = Instant.parse("2026-10-20T12:00:00Z");

  @Autowired private HouseholdDeletionService householdDeletion;
  @Autowired private WatchStatusService watchStatus;
  @Autowired private AuthTestSupport auth;
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private SessionProgressRepository progress;
  @Autowired private AuditColumnFixtures auditColumns;

  /** A ServerAdmin reading its own Profile's activity, and the Movie behind each activity row. */
  record Scope(
      AuthTestSupport.TestIdentity admin, Library library, Map<UUID, UUID> movieByActivity) {}

  @Override
  protected Scope createScope() {
    return new Scope(
        auth.createAdminIdentity(),
        libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary()),
        new HashMap<>());
  }

  @Override
  protected void deleteScope(Scope scope) {
    auth.deleteIdentity(scope.admin());
    movies.deleteAllById(scope.movieByActivity().values());
    libraries.deleteById(scope.library().getId());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var movie = movies.saveAndFlush(MediaEntityFixture.movieWithFile(scope.library()));
    var saved =
        progress.saveAndFlush(
            SessionProgressFixture.progressBuilder(
                    profileId(scope), movie.getFiles().iterator().next().getId())
                .positionSeconds(600)
                .build());
    auditColumns.overwriteLastModifiedOn(
        SESSION_PROGRESS, SESSION_PROGRESS.ID.eq(saved.getId()), NEWEST.minusSeconds(60L * rank));
    scope.movieByActivity().put(saved.getId(), movie.getId());
    return saved.getId();
  }

  @Override
  protected void remove(Scope scope, UUID item) {
    var outcome = watchStatus.markUnwatched(profileId(scope), scope.movieByActivity().get(item));
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
  }

  @Override
  protected ListPage<SessionProgress> page(Scope scope, PageRequest request) {
    return householdDeletion.profileActivity(
        auth.identityOf(scope.admin()), profileId(scope), request);
  }

  @Override
  protected UUID idOf(SessionProgress activity) {
    return activity.getId();
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
    return Optional.empty();
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.empty();
  }

  private static UUID profileId(Scope scope) {
    return scope.admin().profile().getId();
  }
}
