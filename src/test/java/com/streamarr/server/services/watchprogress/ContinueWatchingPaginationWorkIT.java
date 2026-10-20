package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardContinuation;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.AnalyzedSelects;
import com.streamarr.server.support.AuthTestSupport;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Transactional
@Tag("IntegrationTest")
class ContinueWatchingPaginationWorkIT extends AbstractIntegrationTest {
  @Autowired private ContinueWatchingService service;
  @Autowired private AuthTestSupport identities;
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private SessionProgressRepository progress;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper mapper;

  @Test
  @DisplayName("Should bound a continuation query to the selected Profile's progress")
  void shouldBoundAContinuationQueryToTheSelectedProfilesProgress() {
    var profile = identities.createIdentity().profile().getId();
    var otherProfile = identities.createIdentity().profile().getId();
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    for (var index = 0; index < 50; index++) {
      var file =
          MediaFile.builder()
              .libraryId(library.getId())
              .status(MediaFileStatus.MATCHED)
              .filename("movie.mkv")
              .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
              .build();
      var movie =
          movies.saveAndFlush(
              Movie.builder()
                  .title("Movie " + index)
                  .titleSort("Movie " + index)
                  .library(library)
                  .files(Set.of(file))
                  .build());
      if (index >= 32) {
        continue;
      }

      progress.saveAndFlush(
          SessionProgress.builder()
              .sessionId(UUID.randomUUID())
              .profileId(profile)
              .mediaFileId(movie.getFiles().iterator().next().getId())
              .positionSeconds(30)
              .percentComplete(1)
              .durationSeconds(3000)
              .build());
    }

    jdbc.update(
        """
        INSERT INTO session_progress
        SELECT (jsonb_populate_record(NULL::session_progress, to_jsonb(seed) ||
          jsonb_build_object('id', gen_random_uuid(), 'session_id', gen_random_uuid(),
            'profile_id', ?::uuid))).*
        FROM (SELECT * FROM session_progress WHERE profile_id = ? LIMIT 1) seed
        CROSS JOIN generate_series(1, 2000)
        """,
        otherProfile,
        profile);
    jdbc.execute("ANALYZE session_progress, media_file, watch_history");
    var filter =
        MediaFilter.builder()
            .profileId(profile)
            .sortBy(OrderMediaBy.LAST_WATCHED)
            .sortDirection(SortDirection.DESC)
            .build();
    var first = service.getPage(profile, buildForwardOptions(12, filter));
    var plans =
        AnalyzedSelects.builder()
            .dsl(dsl)
            .jdbc(jdbc)
            .json(mapper)
            .build()
            .of(
                () -> {
                  var next =
                      service.getPage(
                          profile, buildForwardContinuation(12, filter, first.items().getLast()));
                  assertThat(next.items()).hasSize(12).doesNotContainAnyElementsOf(first.items());
                  assertThat(next.hasNextPage()).isTrue();
                  assertThat(next.hasPreviousPage()).isTrue();
                });

    assertThat(plans).hasSize(1);
    var plan = plans.getFirst();
    assertThat(plan.at("/0/Plan/Node Type").asString()).isEqualTo("Limit");
    assertThat(plan.at("/0/Plan/Actual Rows").asLong()).isEqualTo(14);
    assertThat(plan.findParents("Relation Name"))
        .filteredOn(node -> node.path("Relation Name").asString().equals("session_progress"))
        .isNotEmpty()
        .allSatisfy(
            node -> {
              assertThat(node.path("Actual Rows").asLong()).isLessThanOrEqualTo(32);
              assertThat(node.path("Rows Removed by Filter").asLong()).isLessThanOrEqualTo(32);
            });
  }
}
