package com.streamarr.server.services.metadata.series;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("IntegrationTest")
@DisplayName("TMDB Series Provider Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TMDBSeriesProviderIT extends AbstractIntegrationTest {

  private static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

  @DynamicPropertySource
  static void configureWireMock(DynamicPropertyRegistry registry) {
    wireMock.start();

    registry.add("tmdb.api.base-url", wireMock::baseUrl);
    registry.add("tmdb.api.token", () -> "test-api-token");
  }

  @Autowired private TMDBSeriesProvider provider;

  @Autowired private LibraryRepository libraryRepository;

  private Library savedLibrary;

  @BeforeAll
  void setupLibrary() {
    savedLibrary = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
  }

  @BeforeEach
  void resetStubs() {
    wireMock.resetAll();
  }

  @AfterAll
  static void tearDown() {
    wireMock.stop();
  }

  // --- search() tests ---

  @Test
  @DisplayName("Should return remote search result when TMDB returns TV results")
  void shouldReturnRemoteSearchResultWhenTmdbReturnsTvResults() {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/tv"))
            .withQueryParam("query", equalTo("Breaking Bad"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "page": 1,
                          "results": [
                            {
                              "id": 1396,
                              "name": "Breaking Bad",
                              "original_name": "Breaking Bad",
                              "first_air_date": "2008-01-20",
                              "overview": "A chemistry teacher diagnosed with cancer.",
                              "popularity": 150.0,
                              "vote_count": 12000,
                              "vote_average": 8.9
                            }
                          ],
                          "total_results": 1,
                          "total_pages": 1
                        }
                        """)));

    var result = provider.search(VideoFileParserResult.builder().title("Breaking Bad").build());

    assertThat(result).isPresent();
    assertThat(result.get().title()).isEqualTo("Breaking Bad");
    assertThat(result.get().externalId()).isEqualTo("1396");
    assertThat(result.get().externalSourceType()).isEqualTo(ExternalSourceType.TMDB);
  }

  @Test
  @DisplayName("Should return empty when TMDB returns no TV results")
  void shouldReturnEmptyWhenTmdbReturnsTvNoResults() {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/tv"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "page": 1,
                          "results": [],
                          "total_results": 0,
                          "total_pages": 0
                        }
                        """)));

    var result = provider.search(VideoFileParserResult.builder().title("Nonexistent Show").build());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when TMDB TV search API returns error")
  void shouldReturnEmptyWhenTmdbTvSearchApiReturnsError() {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/tv"))
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "status_message": "Internal error.",
                          "success": false,
                          "status_code": 11
                        }
                        """)));

    var result = provider.search(VideoFileParserResult.builder().title("Test").build());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should find series by IMDB external ID when external ID provided")
  void shouldFindSeriesByImdbExternalIdWhenExternalIdProvided() {
    wireMock.stubFor(
        get(urlPathEqualTo("/find/tt0903747"))
            .withQueryParam("external_source", equalTo("imdb_id"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "tv_results": [
                            {
                              "id": 1396,
                              "name": "Breaking Bad",
                              "original_name": "Breaking Bad",
                              "first_air_date": "2008-01-20",
                              "overview": "A chemistry teacher diagnosed with cancer.",
                              "popularity": 150.0,
                              "vote_count": 12000,
                              "vote_average": 8.9
                            }
                          ]
                        }
                        """)));

    var result =
        provider.search(
            VideoFileParserResult.builder()
                .title("Breaking Bad")
                .externalId("tt0903747")
                .externalSource(ExternalSourceType.IMDB)
                .build());

    assertThat(result).isPresent();
    assertThat(result.get().title()).isEqualTo("Breaking Bad");
    assertThat(result.get().externalId()).isEqualTo("1396");
    assertThat(result.get().externalSourceType()).isEqualTo(ExternalSourceType.TMDB);
  }

  @Test
  @DisplayName("Should fall back to text search when find by external ID returns no TV results")
  void shouldFallBackToTextSearchWhenFindByExternalIdReturnsNoTvResults() {
    wireMock.stubFor(
        get(urlPathEqualTo("/find/tt9999999"))
            .withQueryParam("external_source", equalTo("imdb_id"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "tv_results": []
                        }
                        """)));

    wireMock.stubFor(
        get(urlPathEqualTo("/search/tv"))
            .withQueryParam("query", equalTo("Some Show"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "page": 1,
                          "results": [
                            {
                              "id": 5555,
                              "name": "Some Show",
                              "original_name": "Some Show",
                              "first_air_date": "2020-01-01",
                              "popularity": 50.0,
                              "vote_count": 500,
                              "vote_average": 7.0
                            }
                          ],
                          "total_results": 1,
                          "total_pages": 1
                        }
                        """)));

    var result =
        provider.search(
            VideoFileParserResult.builder()
                .title("Some Show")
                .externalId("tt9999999")
                .externalSource(ExternalSourceType.IMDB)
                .build());

    assertThat(result).isPresent();
    assertThat(result.get().title()).isEqualTo("Some Show");
    assertThat(result.get().externalId()).isEqualTo("5555");
  }

  // --- getMetadata() tests ---

  @Test
  @DisplayName("Should map basic fields when TMDB returns TV series response")
  void shouldMapBasicFieldsWhenTmdbReturnsTvSeriesResponse() {
    var series = getMetadataFromFullResponse();

    assertThat(series.getTitle()).isEqualTo("Breaking Bad");
    assertThat(series.getTagline()).isEqualTo("All Hail the King.");
    assertThat(series.getSummary())
        .isEqualTo("A chemistry teacher diagnosed with inoperable lung cancer.");
    assertThat(series.getFirstAirDate()).isEqualTo(LocalDate.of(2008, 1, 20));
    assertThat(series.getRuntime()).isEqualTo(47);
  }

  @Test
  @DisplayName("Should map content rating when TMDB returns TV series response")
  void shouldMapContentRatingWhenTmdbReturnsTvSeriesResponse() {
    var series = getMetadataFromFullResponse();

    assertThat(series.getContentRating()).isNotNull();
    assertThat(series.getContentRating().system()).isEqualTo("TV Parental Guidelines");
    assertThat(series.getContentRating().value()).isEqualTo("TV-MA");
    assertThat(series.getContentRating().country()).isEqualTo("US");
  }

  @Test
  @DisplayName("Should map TMDB and IMDB external IDs when TMDB returns TV series response")
  void shouldMapExternalIdsWhenTmdbReturnsTvSeriesResponse() {
    var series = getMetadataFromFullResponse();

    assertThat(series.getExternalIds()).hasSize(2);
    assertThat(series.getExternalIds())
        .extracting("externalSourceType")
        .containsExactlyInAnyOrder(ExternalSourceType.TMDB, ExternalSourceType.IMDB);
  }

  @Test
  @DisplayName("Should map cast in order when TMDB returns TV series response")
  void shouldMapCastInOrderWhenTmdbReturnsTvSeriesResponse() {
    var series = getMetadataFromFullResponse();

    assertThat(series.getCast()).hasSize(2);
    assertThat(series.getCast().get(0).getName()).isEqualTo("Bryan Cranston");
    assertThat(series.getCast().get(0).getSourceId()).isEqualTo("17419");
    assertThat(series.getCast().get(1).getName()).isEqualTo("Aaron Paul");
  }

  @Test
  @DisplayName("Should map studios when TMDB returns TV series response")
  void shouldMapStudiosWhenTmdbReturnsTvSeriesResponse() {
    var series = getMetadataFromFullResponse();

    assertThat(series.getStudios()).hasSize(1);
    assertThat(series.getStudios().iterator().next().getName())
        .isEqualTo("High Bridge Entertainment");
    assertThat(series.getStudios().iterator().next().getSourceId()).isEqualTo("2605");
  }

  @Test
  @DisplayName("Should map genres when TMDB returns TV series response")
  void shouldMapGenresWhenTmdbReturnsTvSeriesResponse() {
    var series = getMetadataFromFullResponse();

    assertThat(series.getGenres()).hasSize(2);
    assertThat(series.getGenres()).extracting("name").containsExactlyInAnyOrder("Drama", "Crime");
  }

  @Test
  @DisplayName("Should map directors when TMDB returns TV series response")
  void shouldMapDirectorsWhenTmdbReturnsTvSeriesResponse() {
    var series = getMetadataFromFullResponse();

    assertThat(series.getDirectors()).hasSize(1);
    assertThat(series.getDirectors().get(0).getName()).isEqualTo("Vince Gilligan");
    assertThat(series.getDirectors().get(0).getSourceId()).isEqualTo("66633");
  }

  @Test
  @DisplayName("Should handle null credits when credits absent from TV response")
  void shouldHandleNullCreditsWhenCreditsAbsentFromTvResponse() {
    stubMinimalSeriesResponse("1396");

    var result = provider.getMetadata(buildSearchResult("1396"), savedLibrary);

    assertThat(result).isPresent();
    assertThat(result.get().entity().getCast()).isEmpty();
    assertThat(result.get().entity().getDirectors()).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when TMDB TV metadata API returns error")
  void shouldReturnEmptyWhenTmdbTvMetadataApiReturnsError() {
    wireMock.stubFor(
        get(urlPathEqualTo("/tv/1396"))
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "status_message": "Internal error.",
                          "success": false,
                          "status_code": 11
                        }
                        """)));

    var result = provider.getMetadata(buildSearchResult("1396"), savedLibrary);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName(
      "Should map original title and compute title sort when TV response includes original name")
  void shouldMapOriginalTitleAndComputeTitleSortWhenResponseIncludesOriginalName() {
    var series = getMetadataFromFullResponse();

    assertThat(series.getOriginalTitle()).isEqualTo("Breaking Bad");
    assertThat(series.getTitleSort()).isEqualTo("Breaking Bad");
  }

  @Test
  @DisplayName("Should build image sources when TV response includes backdrop and poster paths")
  void shouldBuildImageSourcesWhenResponseIncludesBackdropAndPosterPaths() {
    var result = getFullMetadataResult();

    assertThat(result.imageSources()).hasSize(2);
    assertThat(result.imageSources())
        .anyMatch(
            s ->
                s instanceof TmdbImageSource t
                    && t.imageType() == ImageType.POSTER
                    && "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg".equals(t.pathFragment()))
        .anyMatch(
            s ->
                s instanceof TmdbImageSource t
                    && t.imageType() == ImageType.BACKDROP
                    && "/zzWGRQUhBaS2eSBzNkwpT2hKZVh.jpg".equals(t.pathFragment()));
  }

  @Test
  @DisplayName("Should skip content rating when no US rating exists")
  void shouldSkipContentRatingWhenNoUsRatingExists() {
    stubMinimalSeriesResponse(
        "1396",
        """
        ,"content_ratings": {"results": [{"iso_3166_1": "GB", "rating": "18"}]}
        """);

    var result = provider.getMetadata(buildSearchResult("1396"), savedLibrary);

    assertThat(result).isPresent();
    assertThat(result.get().entity().getContentRating()).isNull();
  }

  @Test
  @DisplayName("Should return null runtime when episode run time is absent")
  void shouldReturnNullRuntimeWhenEpisodeRunTimeAbsent() {
    stubMinimalSeriesResponse("1396");

    var result = provider.getMetadata(buildSearchResult("1396"), savedLibrary);

    assertThat(result).isPresent();
    assertThat(result.get().entity().getRuntime()).isNull();
  }

  @Test
  @DisplayName("Should map only TMDB external ID when IMDB ID is absent")
  void shouldMapOnlyTmdbExternalIdWhenImdbIdIsAbsent() {
    stubMinimalSeriesResponse("1396");

    var result = provider.getMetadata(buildSearchResult("1396"), savedLibrary);

    assertThat(result).isPresent();
    assertThat(result.get().entity().getExternalIds()).hasSize(1);
    assertThat(
            result.get().entity().getExternalIds().stream()
                .anyMatch(id -> id.getExternalSourceType() == ExternalSourceType.TMDB))
        .isTrue();
  }

  // --- getSeasonDetails() tests ---

  @Test
  @DisplayName("Should return season details with episodes when TMDB returns season response")
  void shouldReturnSeasonDetailsWhenTmdbReturnsSeasonResponse() {
    wireMock.stubFor(
        get(urlPathEqualTo("/tv/1396/season/1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 3577,
                          "name": "Season 1",
                          "overview": "The first season of Breaking Bad.",
                          "season_number": 1,
                          "air_date": "2008-01-20",
                          "poster_path": "/1BP4xYv9ZG4ZVHkL7ocOziBbSYH.jpg",
                          "episodes": [
                            {
                              "id": 62085,
                              "name": "Pilot",
                              "overview": "Walter White is a chemistry genius.",
                              "episode_number": 1,
                              "season_number": 1,
                              "air_date": "2008-01-20",
                              "runtime": 58,
                              "still_path": "/ydlY3iEN5qYVoW0gRgJyBRC9OjI.jpg"
                            },
                            {
                              "id": 62086,
                              "name": "Cat's in the Bag...",
                              "overview": "Walt and Jesse attempt to dispose of evidence.",
                              "episode_number": 2,
                              "season_number": 1,
                              "air_date": "2008-01-27",
                              "runtime": 48,
                              "still_path": "/tjuDU8g7Pv2xSxgvpH1RlyQbFcq.jpg"
                            }
                          ]
                        }
                        """)));

    var result = provider.getSeasonDetails("1396", 1);

    assertThat(result).isPresent();
    var season = result.get();
    assertThat(season.name()).isEqualTo("Season 1");
    assertThat(season.seasonNumber()).isEqualTo(1);
    assertThat(season.overview()).isEqualTo("The first season of Breaking Bad.");
    assertThat(season.imageSources()).hasSize(1);
    assertThat(season.imageSources().getFirst())
        .isEqualTo(new TmdbImageSource(ImageType.POSTER, "/1BP4xYv9ZG4ZVHkL7ocOziBbSYH.jpg"));
    assertThat(season.airDate()).isEqualTo(LocalDate.of(2008, 1, 20));
    assertThat(season.episodes()).hasSize(2);

    var ep1 = season.episodes().get(0);
    assertThat(ep1.episodeNumber()).isEqualTo(1);
    assertThat(ep1.name()).isEqualTo("Pilot");
    assertThat(ep1.overview()).isEqualTo("Walter White is a chemistry genius.");
    assertThat(ep1.imageSources()).hasSize(1);
    assertThat(ep1.imageSources().getFirst())
        .isEqualTo(new TmdbImageSource(ImageType.STILL, "/ydlY3iEN5qYVoW0gRgJyBRC9OjI.jpg"));
    assertThat(ep1.airDate()).isEqualTo(LocalDate.of(2008, 1, 20));
    assertThat(ep1.runtime()).isEqualTo(58);

    var ep2 = season.episodes().get(1);
    assertThat(ep2.episodeNumber()).isEqualTo(2);
    assertThat(ep2.name()).isEqualTo("Cat's in the Bag...");
    assertThat(ep2.runtime()).isEqualTo(48);
  }

  @Test
  @DisplayName("Should return empty when TMDB season details API returns error")
  void shouldReturnEmptyWhenTmdbSeasonDetailsApiReturnsError() {
    wireMock.stubFor(
        get(urlPathEqualTo("/tv/1396/season/1"))
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "status_message": "Internal error.",
                          "success": false,
                          "status_code": 11
                        }
                        """)));

    var result = provider.getSeasonDetails("1396", 1);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should build season poster image source when TMDB returns poster path")
  void shouldBuildSeasonPosterImageSourceWhenTmdbReturnsPosterPath() {
    wireMock.stubFor(
        get(urlPathEqualTo("/tv/1396/season/1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 3577,
                          "name": "Season 1",
                          "season_number": 1,
                          "poster_path": "/1BP4xYv9ZG4ZVHkL7ocOziBbSYH.jpg",
                          "episodes": []
                        }
                        """)));

    var result = provider.getSeasonDetails("1396", 1);

    assertThat(result).isPresent();
    assertThat(result.get().imageSources()).hasSize(1);
    assertThat(result.get().imageSources().getFirst())
        .isEqualTo(new TmdbImageSource(ImageType.POSTER, "/1BP4xYv9ZG4ZVHkL7ocOziBbSYH.jpg"));
  }

  @Test
  @DisplayName("Should build episode still image source when TMDB returns still path")
  void shouldBuildEpisodeStillImageSourceWhenTmdbReturnsStillPath() {
    wireMock.stubFor(
        get(urlPathEqualTo("/tv/1396/season/1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 3577,
                          "name": "Season 1",
                          "season_number": 1,
                          "episodes": [
                            {
                              "id": 62085,
                              "name": "Pilot",
                              "episode_number": 1,
                              "season_number": 1,
                              "still_path": "/ydlY3iEN5qYVoW0gRgJyBRC9OjI.jpg"
                            }
                          ]
                        }
                        """)));

    var result = provider.getSeasonDetails("1396", 1);

    assertThat(result).isPresent();
    var ep1 = result.get().episodes().getFirst();
    assertThat(ep1.imageSources()).hasSize(1);
    assertThat(ep1.imageSources().getFirst())
        .isEqualTo(new TmdbImageSource(ImageType.STILL, "/ydlY3iEN5qYVoW0gRgJyBRC9OjI.jpg"));
  }

  @Test
  @DisplayName("Should return empty season image sources when poster path is null")
  void shouldReturnEmptySeasonImageSourcesWhenPosterPathIsNull() {
    wireMock.stubFor(
        get(urlPathEqualTo("/tv/1396/season/1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 3577,
                          "name": "Season 1",
                          "season_number": 1,
                          "episodes": []
                        }
                        """)));

    var result = provider.getSeasonDetails("1396", 1);

    assertThat(result).isPresent();
    assertThat(result.get().imageSources()).isEmpty();
  }

  @Test
  @DisplayName("Should return empty episode image sources when still path is null")
  void shouldReturnEmptyEpisodeImageSourcesWhenStillPathIsNull() {
    wireMock.stubFor(
        get(urlPathEqualTo("/tv/1396/season/1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 3577,
                          "name": "Season 1",
                          "season_number": 1,
                          "episodes": [
                            {
                              "id": 62085,
                              "name": "Pilot",
                              "episode_number": 1,
                              "season_number": 1
                            }
                          ]
                        }
                        """)));

    var result = provider.getSeasonDetails("1396", 1);

    assertThat(result).isPresent();
    var ep1 = result.get().episodes().getFirst();
    assertThat(ep1.imageSources()).isEmpty();
  }

  // --- Helpers ---

  private MetadataResult<Series> getFullMetadataResult() {
    stubFullSeriesResponse();
    var result = provider.getMetadata(buildSearchResult("1396"), savedLibrary);
    assertThat(result).isPresent();
    return result.get();
  }

  private Series getMetadataFromFullResponse() {
    return getFullMetadataResult().entity();
  }

  private RemoteSearchResult buildSearchResult(String externalId) {
    return RemoteSearchResult.builder()
        .title("Breaking Bad")
        .externalId(externalId)
        .externalSourceType(ExternalSourceType.TMDB)
        .build();
  }

  private void stubMinimalSeriesResponse(String seriesId) {
    stubMinimalSeriesResponse(seriesId, "");
  }

  private void stubMinimalSeriesResponse(String seriesId, String additionalJson) {
    var body =
        """
        {
          "id": %s,
          "name": "Breaking Bad",
          "original_name": "Breaking Bad",
          "first_air_date": "2008-01-20",
          "overview": "A chemistry teacher.",
          "popularity": 150.0,
          "vote_count": 12000,
          "vote_average": 8.9%s
        }
        """
            .formatted(seriesId, additionalJson);

    wireMock.stubFor(
        get(urlPathEqualTo("/tv/" + seriesId))
            .withQueryParam("append_to_response", equalTo("content_ratings,credits,external_ids"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private void stubFullSeriesResponse() {
    wireMock.stubFor(
        get(urlPathEqualTo("/tv/1396"))
            .withQueryParam("append_to_response", equalTo("content_ratings,credits,external_ids"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 1396,
                          "name": "Breaking Bad",
                          "original_name": "Breaking Bad",
                          "overview": "A chemistry teacher diagnosed with inoperable lung cancer.",
                          "tagline": "All Hail the King.",
                          "first_air_date": "2008-01-20",
                          "backdrop_path": "/zzWGRQUhBaS2eSBzNkwpT2hKZVh.jpg",
                          "poster_path": "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg",
                          "popularity": 150.0,
                          "vote_average": 8.9,
                          "vote_count": 12000,
                          "episode_run_time": [45, 49],
                          "genres": [
                            {"id": 18, "name": "Drama"},
                            {"id": 80, "name": "Crime"}
                          ],
                          "credits": {
                            "id": 1396,
                            "cast": [
                              {
                                "id": 17419,
                                "name": "Bryan Cranston",
                                "character": "Walter White",
                                "order": 0,
                                "adult": false,
                                "gender": 2,
                                "popularity": 50.0,
                                "profile_path": "/7Jahy5LZX2Fo8fGJltMreAI49hC.jpg"
                              },
                              {
                                "id": 84497,
                                "name": "Aaron Paul",
                                "character": "Jesse Pinkman",
                                "order": 1,
                                "adult": false,
                                "gender": 2,
                                "popularity": 40.0
                              }
                            ],
                            "crew": [
                              {
                                "id": 66633,
                                "name": "Vince Gilligan",
                                "job": "Director",
                                "department": "Directing",
                                "adult": false,
                                "gender": 2,
                                "popularity": 30.0
                              }
                            ]
                          },
                          "content_ratings": {
                            "results": [
                              {
                                "iso_3166_1": "US",
                                "rating": "TV-MA"
                              }
                            ]
                          },
                          "external_ids": {
                            "imdb_id": "tt0903747"
                          },
                          "production_companies": [
                            {
                              "id": 2605,
                              "name": "High Bridge Entertainment",
                              "origin_country": "US",
                              "logo_path": "/8M99Dkt23MjQMTTWukq4m5XsEuo.png"
                            }
                          ],
                          "seasons": [
                            {
                              "id": 3577,
                              "name": "Season 1",
                              "overview": "The first season.",
                              "season_number": 1,
                              "air_date": "2008-01-20",
                              "poster_path": "/1BP4xYv9ZG4ZVHkL7ocOziBbSYH.jpg",
                              "episode_count": 7
                            }
                          ]
                        }
                        """)));
  }
}
