package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.SessionProgressFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Continue Watching paging")
class ContinueWatchingPagedListIT
    extends PagedListContract<BaseCollectable<?>, AuthTestSupport.TestIdentity> {

  private static final Instant NEWEST = Instant.parse("2026-10-20T12:00:00Z");

  @Autowired private ContinueWatchingService continueWatching;
  @Autowired private AuthTestSupport auth;
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private SessionProgressRepository progress;
  @Autowired private AuditColumnFixtures auditColumns;

  private final Map<UUID, Library> libraryByProfile = new HashMap<>();

  @Override
  protected AuthTestSupport.TestIdentity createScope() {
    var identity = auth.createIdentity();
    libraryByProfile.put(
        identity.profile().getId(),
        libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary()));
    return identity;
  }

  @Override
  protected void deleteScope(AuthTestSupport.TestIdentity identity) {
    auth.deleteIdentity(identity);
  }

  @Override
  protected UUID add(AuthTestSupport.TestIdentity identity, int rank) {
    var profileId = identity.profile().getId();
    var movie = movies.saveAndFlush(movieWithFile(libraryByProfile.get(profileId)));
    var saved =
        progress.saveAndFlush(
            SessionProgressFixture.progressBuilder(
                    profileId, movie.getFiles().iterator().next().getId())
                .positionSeconds(600)
                .build());
    auditColumns.overwriteLastModifiedOn(
        SESSION_PROGRESS,
        SESSION_PROGRESS.SESSION_ID.eq(saved.getSessionId()),
        NEWEST.minusSeconds(60L * rank));
    return movie.getId();
  }

  @Override
  protected void remove(AuthTestSupport.TestIdentity identity, UUID item) {
    movies.deleteById(item);
  }

  @Override
  protected ListPage<BaseCollectable<?>> page(
      AuthTestSupport.TestIdentity identity, PageRequest request) {
    return continueWatching.getPage(identity.profile().getId(), request);
  }

  @Override
  protected UUID idOf(BaseCollectable<?> item) {
    return item.getId();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.DESC;
  }

  @Override
  protected Optional<Position> positionBeforeArrangedItems(AuthTestSupport.TestIdentity identity) {
    return Optional.empty();
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(AuthTestSupport.TestIdentity identity) {
    return Optional.empty();
  }

  private static Movie movieWithFile(Library library) {
    return Movie.builder()
        .title("Paged")
        .titleSort("Paged")
        .library(library)
        .files(
            Set.of(
                MediaFile.builder()
                    .libraryId(library.getId())
                    .status(MediaFileStatus.MATCHED)
                    .filename(UUID.randomUUID() + ".mkv")
                    .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
                    .build()))
        .build();
  }
}
