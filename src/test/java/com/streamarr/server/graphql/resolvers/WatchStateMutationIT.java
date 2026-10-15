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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag("IntegrationTest")
@DisplayName("Watch State Mutation Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WatchStateMutationIT extends AbstractIntegrationTest {

  private static final String MARK_WATCHED =
      """
      mutation {
        markWatched(input: {itemId: "%s"}) {
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

    markWatched(itemId.toString())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markWatched.item['__typename']").value(kind.typename()))
        .andExpect(jsonPath("$.data.markWatched.item.id").value(itemId.toString()))
        .andExpect(jsonPath("$.data.markWatched.item.watchStatus").value("WATCHED"))
        .andExpect(jsonPath("$.data.markWatched.userErrors").isEmpty());
  }

  @Test
  @DisplayName("Should return an item-not-found error when no item has the ID")
  void shouldReturnAnItemNotFoundErrorWhenNoItemHasTheId() throws Exception {
    var unknownId = UUID.randomUUID();

    markWatched(unknownId.toString())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markWatched.item").value(nullValue()))
        .andExpect(
            jsonPath("$.data.markWatched.userErrors[0].__typename").value("ItemNotFoundError"))
        .andExpect(
            jsonPath("$.data.markWatched.userErrors[0].message")
                .value("This item no longer exists."))
        .andExpect(
            jsonPath("$.data.markWatched.userErrors[0].inputPath").value(contains("itemId")));
    assertThat(
            watchHistoryRepository.findByProfileIdAndCollectableIdIn(
                identity.profile().getId(), List.of(unknownId)))
        .isEmpty();
  }

  @Test
  @DisplayName("Should return an invalid-ID error when the item ID is malformed")
  void shouldReturnAnInvalidIdErrorWhenTheItemIdIsMalformed() throws Exception {
    markWatched("not-a-uuid")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markWatched.item").value(nullValue()))
        .andExpect(jsonPath("$.data.markWatched.userErrors[0].__typename").value("InvalidIdError"))
        .andExpect(
            jsonPath("$.data.markWatched.userErrors[0].message").value("Enter a valid item ID."))
        .andExpect(
            jsonPath("$.data.markWatched.userErrors[0].inputPath").value(contains("itemId")));
  }

  @Test
  @DisplayName("Should refuse to mark the item watched when no Profile is selected")
  void shouldRefuseToMarkTheItemWatchedWhenNoProfileIsSelected() throws Exception {
    var movieId = createItem(ItemKind.MOVIE);

    mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(identity), MARK_WATCHED.formatted(movieId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("PROFILE_REQUIRED"));
    assertThat(
            watchHistoryRepository.findByProfileIdAndCollectableIdIn(
                identity.profile().getId(), List.of(movieId)))
        .isEmpty();
  }

  private ResultActions markWatched(String itemId) throws Exception {
    return mockMvc.perform(
        graphqlRequest(authTestSupport.profileBearer(identity), MARK_WATCHED.formatted(itemId)));
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
