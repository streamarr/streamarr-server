package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.library.LetterIndexRecalculator;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LibraryCountsQueryIT extends AbstractIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private AuthTestSupport auth;
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private LetterIndexRecalculator recalculator;

  @Test
  void shouldExposeAuthoritativeTotalsInSingleAndMultipleLibraryQueriesForANonAdminProfile() {
    var identity = auth.createIdentity();
    try (var http =
        new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)))) {
      var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
      var empty = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
      movies.saveAndFlush(
          Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
      movies.saveAndFlush(Movie.builder().title("123").titleSort("123").library(library).build());
      recalculator.recalculateLetterIndex(library.getId());

      var result =
          http.execute(
              auth.profileBearer(identity),
              """
              { library(id: "%s") { itemCount alphabetIndex { count } }
                libraries { id itemCount } }
              """
                  .formatted(library.getId()));

      assertThat(result.has("errors")).as(result.toPrettyString()).isFalse();
      assertThat(result.at("/data/library/itemCount").asInt()).isEqualTo(2);
      assertThat(result.at("/data/library/alphabetIndex"))
          .extracting(bucket -> bucket.path("count").asInt())
          .containsExactly(1, 1);
      assertThat(result.at("/data/libraries"))
          .anySatisfy(
              item -> {
                assertThat(item.path("id").asString()).isEqualTo(empty.getId().toString());
                assertThat(item.path("itemCount").asInt()).isZero();
              })
          .anySatisfy(
              item -> {
                assertThat(item.path("id").asString()).isEqualTo(library.getId().toString());
                assertThat(item.path("itemCount").asInt()).isEqualTo(2);
              });
    } finally {
      auth.deleteIdentity(identity);
    }
  }
}
