package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardContinuation;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.PageItem;
import java.util.LinkedHashSet;
import org.jooq.DSLContext;
import org.jooq.Select;
import org.jooq.VisitListener;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Transactional
@Tag("IntegrationTest")
class TitlePaginationWorkIT extends AbstractIntegrationTest {
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private MovieService service;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("Should read a late title page without scanning earlier titles")
  void shouldReadALateTitlePageWithoutScanningEarlierTitles() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var anchor =
        movies.saveAndFlush(
            Movie.builder().title("Anchor").titleSort("title180000").library(library).build());
    jdbc.update(
        """
        INSERT INTO base_collectable
        SELECT (jsonb_populate_record(NULL::base_collectable, to_jsonb(seed) ||
          jsonb_build_object('id', gen_random_uuid(), 'title_sort', 'title' || lpad(n::text, 6, '0')))).*
        FROM base_collectable seed CROSS JOIN generate_series(1, 200000) n WHERE seed.id = ?
        """,
        anchor.getId());
    jdbc.update(
        """
        INSERT INTO movie
        SELECT (jsonb_populate_record(NULL::movie, to_jsonb(seed) || jsonb_build_object('id', item.id))).*
        FROM movie seed CROSS JOIN base_collectable item
        WHERE seed.id = ? AND item.library_id = ? AND item.id <> seed.id
        """,
        anchor.getId(),
        library.getId());
    jdbc.execute("ANALYZE base_collectable, movie");

    var statements = new LinkedHashSet<String>();
    var caller = Thread.currentThread();
    var previousListeners = dsl.configuration().visitListenerProviders();
    dsl.configuration()
        .set(
            VisitListener.onVisitStart(
                context -> {
                  if (Thread.currentThread() != caller) {
                    return;
                  }

                  if (context.queryPartsLength() == 1 && context.queryPart() instanceof Select<?>) {
                    statements.add(DSL.using(dsl.dialect()).renderInlined(context.queryPart()));
                  }
                }));
    try {
      var page =
          service.getMoviesWithFilter(
              buildForwardContinuation(
                  50,
                  MediaFilter.builder().libraryId(library.getId()).build(),
                  new PageItem<>(anchor, anchor.getTitleSort())));
      assertThat(page.items()).hasSize(50);
      assertThat(page.hasNextPage()).isTrue();
    } finally {
      dsl.configuration().set(previousListeners);
    }

    assertThat(statements).isNotEmpty();
    for (var statement : statements) {
      var plan =
          objectMapper.readTree(
              jdbc.queryForObject("EXPLAIN (ANALYZE, FORMAT JSON) " + statement, String.class));
      assertThat(plan.findValues("Actual Rows"))
          .isNotEmpty()
          .allSatisfy(count -> assertThat(count.asLong()).isLessThanOrEqualTo(100));
      assertThat(plan.findValues("Rows Removed by Filter"))
          .allSatisfy(count -> assertThat(count.asLong()).isLessThanOrEqualTo(100));
    }
  }
}
