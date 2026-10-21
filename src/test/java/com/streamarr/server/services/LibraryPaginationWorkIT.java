package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.rowsAfter;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.support.AnalyzedSelects;
import java.time.LocalDate;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibraryPaginationWorkIT extends AbstractIntegrationTest {

  private static final int ITEMS = 1_000;
  private static final int ANCHOR_RANK = 500;
  private static final LocalDate FIRST_RELEASE = LocalDate.of(2000, 1, 1);

  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private MovieService service;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PlatformTransactionManager transactions;

  private Library library;
  private Movie anchor;

  // Every case reads the same Library, so it is seeded once.
  @BeforeAll
  void seedTheLibrary() {
    library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    anchor =
        movies.saveAndFlush(
            Movie.builder()
                .title("Anchor")
                .titleSort("title%06d".formatted(ANCHOR_RANK))
                .releaseDate(FIRST_RELEASE.plusDays(ANCHOR_RANK))
                .runtime(ANCHOR_RANK)
                .library(library)
                .build());
    new TransactionTemplate(transactions).executeWithoutResult(_ -> seedItemsLike(anchor));
  }

  @AfterAll
  void deleteTheLibrary() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            _ -> {
              jdbc.update(
                  "DELETE FROM movie WHERE id IN (SELECT id FROM base_collectable WHERE library_id = ?)",
                  library.getId());
              jdbc.update("DELETE FROM base_collectable WHERE library_id = ?", library.getId());
            });
    libraries.deleteById(library.getId());
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "TITLE, ASC",
    "TITLE, DESC",
    "RELEASE_DATE, ASC",
    "RELEASE_DATE, DESC",
    "RUNTIME, ASC",
    "RUNTIME, DESC"
  })
  @DisplayName("Should read a late page without scanning the items before it")
  void shouldReadALatePageWithoutScanningTheItemsBeforeIt(
      OrderMediaBy sort, SortDirection direction) {
    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(sort)
            .sortDirection(direction)
            .build();

    var plans =
        new TransactionTemplate(transactions)
            .execute(
                _ -> {
                  preferAnyIndexThatServesTheOrder();
                  return AnalyzedSelects.builder()
                      .dsl(dsl)
                      .jdbc(jdbc)
                      .json(objectMapper)
                      .build()
                      .of(
                          () -> {
                            var page =
                                service.getMoviesWithFilter(
                                    filter,
                                    rowsAfter(50, new Row<>(anchor, positionOf(anchor, sort))));
                            assertThat(page.rows()).hasSize(50);
                            assertThat(page.hasNextPage()).isTrue();
                          });
                });

    assertThat(plans).isNotEmpty();
    for (var plan : plans) {
      assertThat(plan.findValues("Actual Rows"))
          .as(plan.toPrettyString())
          .isNotEmpty()
          .allSatisfy(count -> assertThat(count.asLong()).isLessThanOrEqualTo(100));
      assertThat(plan.findValues("Rows Removed by Filter"))
          .as(plan.toPrettyString())
          .allSatisfy(count -> assertThat(count.asLong()).isLessThanOrEqualTo(100));
    }
  }

  // Item n has title "title" + n, released n days after FIRST_RELEASE and n minutes long. Every
  // tenth item has no release date or runtime, so the list places items without a value.
  private void seedItemsLike(Movie anchor) {
    jdbc.update(
        """
        INSERT INTO base_collectable
        SELECT (jsonb_populate_record(NULL::base_collectable, to_jsonb(seed) ||
          jsonb_build_object('id', gen_random_uuid(), 'title_sort', 'title' || lpad(n::text, 6, '0')))).*
        FROM base_collectable seed CROSS JOIN generate_series(1, ?) n WHERE seed.id = ?
        """,
        ITEMS,
        anchor.getId());
    jdbc.update(
        """
        INSERT INTO movie
        SELECT (jsonb_populate_record(NULL::movie, to_jsonb(seed) || jsonb_build_object(
          'id', item.id,
          'release_date', CASE WHEN rank % 10 = 0 THEN NULL ELSE ?::date + rank END,
          'runtime', CASE WHEN rank % 10 = 0 THEN NULL ELSE rank END))).*
        FROM movie seed
        CROSS JOIN LATERAL (
          SELECT id, substring(title_sort FROM 6)::int AS rank
          FROM base_collectable WHERE library_id = ? AND id <> seed.id) item
        WHERE seed.id = ?
        """,
        FIRST_RELEASE, library.getId(), anchor.getId());
    jdbc.execute("ANALYZE base_collectable, movie");
  }

  // The planner weighs an index against a sequential scan and a sort by estimated cost, which
  // moves with table size and statistics. Ruling both out makes it use an index whenever one
  // serves the page, so the bound tests the indexes alone. Without one, the page still sorts every
  // item or filters out the items before the anchor, and either exceeds the bound.
  private void preferAnyIndexThatServesTheOrder() {
    jdbc.execute("SET LOCAL enable_seqscan = off");
    jdbc.execute("SET LOCAL enable_sort = off");
  }

  private static Position positionOf(Movie anchor, OrderMediaBy sort) {
    var value =
        switch (sort) {
          case TITLE -> new SortValue.OfText(anchor.getTitleSort());
          case RELEASE_DATE -> new SortValue.OfDate(anchor.getReleaseDate());
          case RUNTIME -> new SortValue.OfNumber(anchor.getRuntime());
          case ADDED, LAST_WATCHED -> throw new IllegalArgumentException(sort.name());
        };
    return new Position(value, anchor.getId());
  }
}
