package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.services.watchprogress.ContinueWatchingService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@EnableDgsTest
@SpringBootTest(classes = {ContinueWatchingResolver.class, PaginationService.class})
@DisplayName("Continue Watching Resolver Tests")
class ContinueWatchingResolverTest {

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @MockitoBean private ContinueWatchingService continueWatchingService;

  @Nested
  @DisplayName("continueWatching query")
  class ContinueWatchingQueryTests {

    @Test
    @DisplayName("Should delegate to service with first parameter")
    void shouldDelegateToServiceWithFirstParameter() {
      var movie = Movie.builder().title("Test Movie").titleSort("Test Movie").build();
      movie.setId(UUID.randomUUID());

      when(continueWatchingService.getContinueWatching(USER_ID, 10)).thenReturn(List.of(movie));

      List<String> titles =
          dgsQueryExecutor.executeAndExtractJsonPath(
              "{ continueWatching(first: 10) { ... on Movie { title } } }",
              "data.continueWatching[*].title");

      assertThat(titles).containsExactly("Test Movie");
    }

    @Test
    @DisplayName("Should return empty list when no items in progress")
    void shouldReturnEmptyListWhenNoItemsInProgress() {
      when(continueWatchingService.getContinueWatching(USER_ID, 20)).thenReturn(List.of());

      List<Object> results =
          dgsQueryExecutor.executeAndExtractJsonPath(
              "{ continueWatching(first: 20) { ... on Movie { title } } }",
              "data.continueWatching");

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Should resolve Movie type in union")
    void shouldResolveMovieTypeInUnion() {
      var movie = Movie.builder().title("Movie Item").titleSort("Movie Item").build();
      movie.setId(UUID.randomUUID());

      when(continueWatchingService.getContinueWatching(USER_ID, 20)).thenReturn(List.of(movie));

      String typename =
          dgsQueryExecutor.executeAndExtractJsonPath(
              "{ continueWatching(first: 20) { __typename } }",
              "data.continueWatching[0].__typename");

      assertThat(typename).isEqualTo("Movie");
    }

    @Test
    @DisplayName("Should resolve Episode type in union")
    void shouldResolveEpisodeTypeInUnion() {
      var episode = Episode.builder().title("Episode Item").episodeNumber(1).build();
      episode.setId(UUID.randomUUID());

      when(continueWatchingService.getContinueWatching(USER_ID, 20)).thenReturn(List.of(episode));

      String typename =
          dgsQueryExecutor.executeAndExtractJsonPath(
              "{ continueWatching(first: 20) { __typename } }",
              "data.continueWatching[0].__typename");

      assertThat(typename).isEqualTo("Episode");
    }

    @Test
    @DisplayName("Should return error when first is negative")
    void shouldReturnErrorWhenFirstIsNegative() {
      var result =
          dgsQueryExecutor.execute("{ continueWatching(first: -1) { ... on Movie { title } } }");

      assertThat(result.getErrors()).isNotEmpty();
      assertThat(result.getErrors().getFirst().getMessage()).contains("first must not be negative");
    }
  }

  @Nested
  @DisplayName("Type Resolver")
  class TypeResolverTests {

    @Test
    @DisplayName("Should throw for unsupported media type")
    void shouldThrowForUnsupportedMediaType() {
      var resolver =
          new ContinueWatchingResolver(
              mock(ContinueWatchingService.class), new PaginationService());
      var unsupported = new Object();

      assertThatThrownBy(() -> resolver.resolveContinueWatchingMedia(unsupported))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unknown continue watching media type");
    }
  }
}
