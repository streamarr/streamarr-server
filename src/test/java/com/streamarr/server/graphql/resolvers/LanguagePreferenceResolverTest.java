package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.profileScopedBuilder;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildEpisode;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildMovie;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeason;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeLanguagePreferenceRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.graphql.dataloaders.LanguagePreferenceDataLoader;
import com.streamarr.server.graphql.dto.Me;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.language.LanguagePreferenceService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(
    classes = {
      LanguagePreferenceResolver.class,
      LanguagePreferenceDataLoader.class,
      JacksonAutoConfiguration.class,
      StreamarrDataFetcherExceptionHandler.class,
      LanguagePreferenceResolverTest.FakeLanguagePreferences.class,
      LanguagePreferenceResolverTest.MeRoot.class
    })
@DisplayName("Language Preference Resolver Tests")
class LanguagePreferenceResolverTest {

  private static final String PREFERENCE_FIELDS =
      """
      languagePreference {
        audioLanguages subtitleLanguage subtitleMode audioSource subtitleSource
      }
      """;

  @Autowired private DgsQueryExecutor executor;
  @Autowired private ObjectMapper mapper;
  @Autowired private FakeAuthorizationService authorization;
  @Autowired private AtomicReference<AuthenticatedIdentity> identity;
  @Autowired private FakeMovieRepository movies;
  @Autowired private FakeSeriesRepository seriesRepository;
  @Autowired private FakeSeasonRepository seasons;
  @Autowired private FakeEpisodeRepository episodes;

  private Series series;
  private Episode episode;
  private Movie movie;

  // The Me fields beside languagePreferences have their own resolver tests.
  @DgsComponent
  static class MeRoot {

    @DgsQuery
    public Me me() {
      return Me.builder().build();
    }
  }

  @TestConfiguration
  static class FakeLanguagePreferences {

    @Bean
    AtomicReference<AuthenticatedIdentity> identity() {
      return new AtomicReference<>();
    }

    @Bean
    FakeAuthorizationService authorizationService(AtomicReference<AuthenticatedIdentity> identity) {
      return new FakeAuthorizationService(identity::get);
    }

    @Bean
    FakeMovieRepository movies() {
      return new FakeMovieRepository();
    }

    @Bean
    FakeSeriesRepository seriesRepository() {
      return new FakeSeriesRepository();
    }

    @Bean
    FakeSeasonRepository seasons() {
      return new FakeSeasonRepository();
    }

    @Bean
    FakeEpisodeRepository episodes() {
      return new FakeEpisodeRepository();
    }

    @Bean
    LanguagePreferenceService languagePreferenceService(
        FakeAuthorizationService authorizationService,
        FakeMovieRepository movies,
        FakeSeriesRepository seriesRepository,
        FakeSeasonRepository seasons,
        FakeEpisodeRepository episodes) {
      return new LanguagePreferenceService(
          authorizationService,
          new FakeLanguagePreferenceRepository(movies, seriesRepository, seasons, episodes),
          movies,
          seriesRepository,
          seasons,
          episodes);
    }
  }

  @BeforeEach
  void watchAsANewProfileWithOneEpisodeAndOneMovie() {
    identity.set(profileScopedBuilder().build());
    authorization.allowAll();
    series = seriesRepository.save(buildSeries("Series"));
    var season = buildSeason("Season 1", 1);
    season.setSeries(series);
    seasons.save(season);
    var unsavedEpisode = buildEpisode("Episode 1", 1);
    unsavedEpisode.setSeason(season);
    episode = episodes.save(unsavedEpisode);
    movie = movies.save(buildMovie("Movie"));
  }

  @AfterEach
  void forgetTheTitles() {
    movies.deleteAll();
    episodes.deleteAll();
    seasons.deleteAll();
    seriesRepository.deleteAll();
  }

  @Test
  @DisplayName("Should return the stored preferences when the Profile sets its languages")
  void shouldReturnTheStoredPreferencesWhenTheProfileSetsItsLanguages() {
    var payload =
        mutate(
            "setLanguagePreferences",
            "SetLanguagePreferencesInput",
            "languagePreferences { audioLanguages subtitleLanguage subtitleMode }",
            Map.of(
                "audioLanguages", List.of("JA", "PT_BR"),
                "subtitleLanguage", "EN",
                "subtitleMode", "ALWAYS"));

    assertThat(payload.path("userErrors").size()).isZero();
    assertThat(payload.path("languagePreferences"))
        .isEqualTo(
            mapper.valueToTree(
                Map.of(
                    "audioLanguages", List.of("JA", "PT_BR"),
                    "subtitleLanguage", "EN",
                    "subtitleMode", "ALWAYS")));
    assertThat(
            execute("query { me { languagePreferences { audioLanguages } } }")
                .at("/data/me/languagePreferences/audioLanguages"))
        .isEqualTo(mapper.valueToTree(List.of("JA", "PT_BR")));
  }

  @Test
  @DisplayName("Should refuse the subtitle mode when it is none of OFF, FORCED and ALWAYS")
  void shouldRefuseTheSubtitleModeWhenItIsNoneOfOffForcedAndAlways() {
    var response =
        execute(
            """
            mutation {
              setLanguagePreferences(input: {audioLanguages: [JA], subtitleMode: FOREIGN_AUDIO}) {
                languagePreferences { subtitleMode } } }
            """);

    assertThat(response.at("/errors/0/extensions/classification").asString())
        .isEqualTo("ValidationError");
    assertThat(
            execute("query { me { languagePreferences { audioLanguages subtitleMode } } }")
                .at("/data/me/languagePreferences"))
        .isEqualTo(
            mapper.valueToTree(Map.of("audioLanguages", List.of(), "subtitleMode", "FORCED")));
  }

  @Test
  @DisplayName("Should point at the list when the Profile names six audio languages")
  void shouldPointAtTheListWhenTheProfileNamesSixAudioLanguages() {
    var payload =
        setLanguages(
            List.of("EN", "FR", "DE", "ES", "IT", "JA"),
            "... on TooManyAudioLanguagesError { maximum }");

    assertThat(payload.path("languagePreferences").isNull()).isTrue();
    var error = payload.at("/userErrors/0");
    assertThat(error.path("__typename").asString()).isEqualTo("TooManyAudioLanguagesError");
    assertThat(error.path("inputPath")).isEqualTo(mapper.valueToTree(List.of("audioLanguages")));
    assertThat(error.path("maximum").asInt()).isEqualTo(5);
    assertThat(error.path("message").asString()).isNotBlank();
  }

  @Test
  @DisplayName("Should point at the second entry when the Profile names one language twice")
  void shouldPointAtTheSecondEntryWhenTheProfileNamesOneLanguageTwice() {
    var payload =
        setLanguages(List.of("EN", "FR", "EN"), "... on RepeatedAudioLanguageError { language }");

    var error = payload.at("/userErrors/0");
    assertThat(error.path("__typename").asString()).isEqualTo("RepeatedAudioLanguageError");
    assertThat(error.path("inputPath"))
        .isEqualTo(mapper.valueToTree(List.of("audioLanguages", "2")));
    assertThat(error.path("language").asString()).isEqualTo("EN");
  }

  @Test
  @DisplayName("Should return null preferences when no Profile is selected")
  void shouldReturnNullPreferencesWhenNoProfileIsSelected() {
    identity.set(accountScopedBuilder().build());

    var response = execute("query { me { languagePreferences { audioLanguages } } }");

    assertThat(response.at("/data/me/languagePreferences").isNull()).isTrue();
  }

  @Test
  @DisplayName("Should return the series with its override when the series' audio is set")
  void shouldReturnTheSeriesWithItsOverrideWhenTheSeriesAudioIsSet() {
    var payload = setOverride(Map.of("itemId", series.getId().toString(), "audioLanguage", "JA"));

    assertThat(payload.path("userErrors").size()).isZero();
    assertThat(payload.at("/item/__typename").asString()).isEqualTo("Series");
    assertThat(payload.at("/item/languagePreference/audioLanguages"))
        .isEqualTo(mapper.valueToTree(List.of("JA")));
    assertThat(payload.at("/item/languagePreference/audioSource").asString()).isEqualTo("SERIES");
  }

  @Test
  @DisplayName("Should keep a field the input leaves out and clear a field the input sets to null")
  void shouldKeepAFieldTheInputLeavesOutAndClearAFieldTheInputSetsToNull() {
    setOverride(
        Map.of(
            "itemId", episode.getId().toString(),
            "audioLanguage", "JA",
            "subtitleLanguage", "EN"));
    var subtitleCleared = new HashMap<String, Object>();
    subtitleCleared.put("itemId", episode.getId().toString());
    subtitleCleared.put("subtitleLanguage", null);

    var payload = setOverride(subtitleCleared);

    var preference = payload.at("/item/languagePreference");
    assertThat(payload.at("/item/__typename").asString()).isEqualTo("Episode");
    assertThat(preference.path("audioLanguages")).isEqualTo(mapper.valueToTree(List.of("JA")));
    assertThat(preference.path("audioSource").asString()).isEqualTo("TITLE");
    assertThat(preference.path("subtitleLanguage").isNull()).isTrue();
    assertThat(preference.path("subtitleSource").asString()).isEqualTo("PROFILE");
  }

  @Test
  @DisplayName(
      "Should return the movie with the Profile's preferences when its override is cleared")
  void shouldReturnTheMovieWithTheProfilesPreferencesWhenItsOverrideIsCleared() {
    setOverride(Map.of("itemId", movie.getId().toString(), "subtitleMode", "ALWAYS"));

    var payload =
        mutate(
            "clearLanguageOverride",
            "ClearLanguageOverrideInput",
            "item { __typename ... on Movie { " + PREFERENCE_FIELDS + " } }",
            Map.of("itemId", movie.getId().toString()));

    assertThat(payload.at("/item/__typename").asString()).isEqualTo("Movie");
    assertThat(payload.at("/item/languagePreference/subtitleMode").asString()).isEqualTo("FORCED");
    assertThat(payload.at("/item/languagePreference/subtitleSource").asString())
        .isEqualTo("PROFILE");
  }

  @Test
  @DisplayName("Should return the invalid ID error when the item ID is not a UUID")
  void shouldReturnTheInvalidIdErrorWhenTheItemIdIsNotAUuid() {
    var set = setOverride(Map.of("itemId", "not-a-uuid", "audioLanguage", "JA"));
    var cleared =
        mutate(
            "clearLanguageOverride",
            "ClearLanguageOverrideInput",
            "item { __typename }",
            Map.of("itemId", "not-a-uuid"));

    assertThat(List.of(set, cleared))
        .allSatisfy(
            payload -> {
              assertThat(payload.path("item").isNull()).isTrue();
              assertThat(payload.at("/userErrors/0/__typename").asString())
                  .isEqualTo("InvalidIdError");
              assertThat(payload.at("/userErrors/0/inputPath"))
                  .isEqualTo(mapper.valueToTree(List.of("itemId")));
            });
  }

  @Test
  @DisplayName("Should return the item not found error when no title has the ID")
  void shouldReturnTheItemNotFoundErrorWhenNoTitleHasTheId() {
    var payload =
        setOverride(
            Map.of("itemId", "00000000-0000-0000-0000-000000000099", "audioLanguage", "JA"));

    assertThat(payload.path("item").isNull()).isTrue();
    assertThat(payload.at("/userErrors/0/__typename").asString()).isEqualTo("ItemNotFoundError");
    assertThat(payload.at("/userErrors/0/inputPath"))
        .isEqualTo(mapper.valueToTree(List.of("itemId")));
  }

  @Test
  @DisplayName("Should expose a forbidden top-level error when the viewer may not set languages")
  void shouldExposeAForbiddenTopLevelErrorWhenTheViewerMayNotSetLanguages() {
    authorization.denyAll();

    var response =
        execute(
            """
            mutation { setLanguageOverride(input: {itemId: "%s", audioLanguage: JA}) {
              item { __typename } } }
            """
                .formatted(series.getId()));

    assertThat(response.at("/errors/0/extensions/code").asString()).isEqualTo("FORBIDDEN");
  }

  private JsonNode setLanguages(List<String> audioLanguages, String errorFields) {
    return mutate(
        "setLanguagePreferences",
        "SetLanguagePreferencesInput",
        """
        languagePreferences { audioLanguages }
        userErrors {
          __typename
          ... on MutationError { message }
          ... on InputMutationError { inputPath }
          %s
        }
        """
            .formatted(errorFields),
        Map.of("audioLanguages", audioLanguages, "subtitleMode", "FORCED"));
  }

  private JsonNode setOverride(Map<String, Object> input) {
    return mutate(
        "setLanguageOverride",
        "SetLanguageOverrideInput",
        """
        item {
          __typename
          ... on Series { %1$s }
          ... on Episode { %1$s }
        }
        userErrors { __typename ... on InputMutationError { inputPath } }
        """
            .formatted(PREFERENCE_FIELDS),
        input);
  }

  private JsonNode mutate(
      String mutation, String inputType, String selection, Map<String, Object> input) {
    var fields =
        selection.contains("userErrors")
            ? selection
            : selection + " userErrors { __typename ... on InputMutationError { inputPath } }";
    var response =
        mapper.<JsonNode>valueToTree(
            executor
                .execute(
                    """
                    mutation($input: %s!) { %s(input: $input) { %s } }
                    """
                        .formatted(inputType, mutation, fields),
                    Map.of("input", input))
                .toSpecification());
    assertThat(response.has("errors")).as("errors in %s", response).isFalse();
    return response.at("/data/" + mutation);
  }

  private JsonNode execute(String document) {
    return mapper.valueToTree(executor.execute(document).toSpecification());
  }
}
