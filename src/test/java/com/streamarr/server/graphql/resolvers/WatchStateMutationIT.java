package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag("IntegrationTest")
@DisplayName("Watch State Mutation Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WatchStateMutationIT extends AbstractIntegrationTest {

  private static final String MARK_WATCHED = "markWatched";
  private static final String MARK_UNWATCHED = "markUnwatched";

  private static final String MARK_MUTATION =
      """
      mutation {
        %s(input: {itemId: "%s"}) {
          item {
            __typename
            ... on Movie { id watchStatus }
            ... on Episode { id watchStatus }
            ... on Season { id watchStatus }
            ... on Series { id watchStatus }
          }
          userErrors {
            __typename
            ... on MutationError { message }
            ... on InputMutationError { inputPath }
          }
        }
      }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;

  private Library library;
  private AuthTestSupport.TestIdentity identity;

  @BeforeAll
  void createLibraryAndIdentity() {
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    identity = authTestSupport.createIdentity();
  }

  @AfterAll
  void deleteIdentity() {
    authTestSupport.deleteIdentity(identity);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ItemKind.class)
  @DisplayName("Should return the item as watched when the Profile marks it watched")
  void shouldReturnTheItemAsWatchedWhenTheProfileMarksItWatched(ItemKind kind) throws Exception {
    var itemId = createItem(kind);

    expectMarkedItem(mark(MARK_WATCHED, itemId), MARK_WATCHED, kind, itemId)
        .andExpect(jsonPath("$.data.markWatched.item.watchStatus").value("WATCHED"));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ItemKind.class)
  @DisplayName(
      "Should return the item as unwatched when the Profile marks a watched item unwatched")
  void shouldReturnTheItemAsUnwatchedWhenTheProfileMarksAWatchedItemUnwatched(ItemKind kind)
      throws Exception {
    var itemId = createItem(kind);
    mark(MARK_WATCHED, itemId).andExpect(jsonPath("$.data.markWatched.userErrors").isEmpty());

    expectMarkedItem(mark(MARK_UNWATCHED, itemId), MARK_UNWATCHED, kind, itemId)
        .andExpect(jsonPath("$.data.markUnwatched.item.watchStatus").value("UNWATCHED"));
  }

  @Test
  @DisplayName(
      "Should return each item in its resulting state when one request marks it watched then"
          + " unwatched")
  void shouldReturnEachItemInItsResultingStateWhenOneRequestMarksItWatchedThenUnwatched()
      throws Exception {
    var movieId = createItem(ItemKind.MOVIE);
    var markBothQuery =
        """
        mutation {
          watched: markWatched(input: {itemId: "%1$s"}) {
            item { ... on Movie { watchStatus } }
          }
          unwatched: markUnwatched(input: {itemId: "%1$s"}) {
            item { ... on Movie { watchStatus } }
          }
        }
        """
            .formatted(movieId);

    mockMvc
        .perform(graphqlRequest(authTestSupport.profileBearer(identity), markBothQuery))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.watched.item.watchStatus").value("WATCHED"))
        .andExpect(jsonPath("$.data.unwatched.item.watchStatus").value("UNWATCHED"));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {MARK_WATCHED, MARK_UNWATCHED})
  @DisplayName("Should return an item-not-found error when no item has the ID")
  void shouldReturnAnItemNotFoundErrorWhenNoItemHasTheId(String mutation) throws Exception {
    var unknownId = UUID.randomUUID();

    expectInputError(mark(mutation, unknownId), mutation, "ItemNotFoundError")
        .andExpect(
            jsonPath("$.data.%s.userErrors[0].message", mutation)
                .value("This item no longer exists."));
    assertThat(
            watchHistoryRepository.findByProfileIdAndCollectableIdIn(
                identity.profile().getId(), List.of(unknownId)))
        .isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {MARK_WATCHED, MARK_UNWATCHED})
  @DisplayName("Should return an invalid-ID error when the item ID is malformed")
  void shouldReturnAnInvalidIdErrorWhenTheItemIdIsMalformed(String mutation) throws Exception {
    expectInputError(mark(mutation, "not-a-uuid"), mutation, "InvalidIdError")
        .andExpect(
            jsonPath("$.data.%s.userErrors[0].message", mutation).value("Enter a valid item ID."));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {MARK_WATCHED, MARK_UNWATCHED})
  @DisplayName("Should refuse to mark the item when no Profile is selected")
  void shouldRefuseToMarkTheItemWhenNoProfileIsSelected(String mutation) throws Exception {
    var movieId = createItem(ItemKind.MOVIE);

    mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(identity),
                MARK_MUTATION.formatted(mutation, movieId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("PROFILE_REQUIRED"));
    assertThat(
            watchHistoryRepository.findByProfileIdAndCollectableIdIn(
                identity.profile().getId(), List.of(movieId)))
        .isEmpty();
  }

  private ResultActions mark(String mutation, Object itemId) throws Exception {
    return mockMvc.perform(
        graphqlRequest(
            authTestSupport.profileBearer(identity), MARK_MUTATION.formatted(mutation, itemId)));
  }

  private static ResultActions expectMarkedItem(
      ResultActions result, String mutation, ItemKind kind, UUID itemId) throws Exception {
    return result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.%s.item['__typename']", mutation).value(kind.typename()))
        .andExpect(jsonPath("$.data.%s.item.id", mutation).value(itemId.toString()))
        .andExpect(jsonPath("$.data.%s.userErrors", mutation).isEmpty());
  }

  private static ResultActions expectInputError(
      ResultActions result, String mutation, String errorType) throws Exception {
    return result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.%s.item", mutation).value(nullValue()))
        .andExpect(jsonPath("$.data.%s.userErrors[0].__typename", mutation).value(errorType))
        .andExpect(
            jsonPath("$.data.%s.userErrors[0].inputPath", mutation).value(contains("itemId")));
  }

  private UUID createItem(ItemKind kind) {
    return switch (kind) {
      case MOVIE -> createMovie().getId();
      case EPISODE -> createEpisode(createSeason(createSeries())).getId();
      case SEASON -> {
        var season = createSeason(createSeries());
        createEpisode(season);
        yield season.getId();
      }
      case SERIES -> {
        var series = createSeries();
        createEpisode(createSeason(series));
        yield series.getId();
      }
    };
  }

  private Movie createMovie() {
    return movieRepository.saveAndFlush(
        Movie.builder().title("Marked Movie").library(library).build());
  }

  private Series createSeries() {
    return seriesRepository.saveAndFlush(
        Series.builder()
            .title("Marked Series")
            .titleSort("Marked Series")
            .library(library)
            .build());
  }

  private Season createSeason(Series series) {
    return seasonRepository.saveAndFlush(
        Season.builder().seasonNumber(1).series(series).library(library).build());
  }

  private Episode createEpisode(Season season) {
    return episodeRepository.saveAndFlush(
        Episode.builder().episodeNumber(1).season(season).library(library).build());
  }

  enum ItemKind {
    MOVIE("Movie"),
    EPISODE("Episode"),
    SEASON("Season"),
    SERIES("Series");

    private final String typename;

    ItemKind(String typename) {
      this.typename = typename;
    }

    String typename() {
      return typename;
    }
  }
}
