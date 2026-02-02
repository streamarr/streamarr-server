package com.streamarr.server.services.metadata.movie;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
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

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("IntegrationTest")
@DisplayName("TMDB Movie Provider Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TMDBMovieProviderIT extends AbstractIntegrationTest {

  private static final WireMockServer wireMock =
      new WireMockServer(wireMockConfig().dynamicPort());

  @DynamicPropertySource
  static void configureWireMock(DynamicPropertyRegistry registry) {
    wireMock.start();

    registry.add("tmdb.api.base-url", wireMock::baseUrl);
    registry.add("tmdb.api.token", () -> "test-api-token");
  }

  @Autowired private TMDBMovieProvider provider;

  @Autowired private LibraryRepository libraryRepository;

  private com.streamarr.server.domain.Library savedLibrary;

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
  @DisplayName("Should return remote search result when TMDB returns results")
  void shouldReturnRemoteSearchResultWhenTmdbReturnsResults() {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .withQueryParam("query", equalTo("Inception"))
            .withQueryParam("year", equalTo("2010"))
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
                              "id": 27205,
                              "title": "Inception",
                              "release_date": "2010-07-16",
                              "adult": false,
                              "popularity": 85.0,
                              "vote_count": 30000,
                              "vote_average": 8.4,
                              "video": false
                            }
                          ],
                          "total_results": 1,
                          "total_pages": 1
                        }
                        """)));

    var result =
        provider.search(
            VideoFileParserResult.builder().title("Inception").year("2010").build());

    assertThat(result).isPresent();
    assertThat(result.get().title()).isEqualTo("Inception");
    assertThat(result.get().externalId()).isEqualTo("27205");
    assertThat(result.get().externalSourceType()).isEqualTo(ExternalSourceType.TMDB);
  }

  @Test
  @DisplayName("Should return empty when TMDB returns no results")
  void shouldReturnEmptyWhenTmdbReturnsNoResults() {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
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

    var result =
        provider.search(
            VideoFileParserResult.builder().title("Nonexistent Movie").build());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when TMDB search API returns error")
  void shouldReturnEmptyWhenTmdbSearchApiReturnsError() {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
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

    var result =
        provider.search(VideoFileParserResult.builder().title("Test").build());

    assertThat(result).isEmpty();
  }

  // --- getMetadata() tests ---

  @Test
  @DisplayName("Should map complete movie when TMDB returns full response")
  void shouldMapCompleteMovieWhenTmdbReturnsFullResponse() {
    stubFullMovieResponse();

    var searchResult = buildSearchResult("27205");

    var result = provider.getMetadata(searchResult, savedLibrary);

    assertThat(result).isPresent();
    var movie = result.get();
    assertThat(movie.getTitle()).isEqualTo("Inception");
    assertThat(movie.getTagline()).isEqualTo("Your mind is the scene of the crime.");
    assertThat(movie.getSummary())
        .isEqualTo(
            "A thief who steals corporate secrets through dream-sharing technology.");
    assertThat(movie.getReleaseDate()).isEqualTo(LocalDate.of(2010, 7, 16));
    assertThat(movie.getContentRating()).isNotNull();
    assertThat(movie.getContentRating().system()).isEqualTo("MPAA");
    assertThat(movie.getContentRating().value()).isEqualTo("PG-13");
    assertThat(movie.getContentRating().country()).isEqualTo("US");
    assertThat(movie.getExternalIds()).hasSize(2);
    assertThat(movie.getCast()).hasSize(2);
    assertThat(movie.getCast().get(0).getName()).isEqualTo("Leonardo DiCaprio");
    assertThat(movie.getCast().get(0).getSourceId()).isEqualTo("6193");
    assertThat(movie.getCast().get(1).getName()).isEqualTo("Tom Hardy");
    assertThat(movie.getStudios()).hasSize(1);
    assertThat(movie.getStudios().iterator().next().getName())
        .isEqualTo("Legendary Entertainment");
    assertThat(movie.getStudios().iterator().next().getSourceId()).isEqualTo("923");
    assertThat(movie.getLibrary()).isEqualTo(savedLibrary);
  }

  @Test
  @DisplayName("Should map only TMDB external ID when IMDB ID is blank")
  void shouldMapOnlyTmdbExternalIdWhenImdbIdIsBlank() {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/27205"))
            .withQueryParam("append_to_response", equalTo("credits,releases"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 27205,
                          "title": "Inception",
                          "imdb_id": "",
                          "release_date": "2010-07-16",
                          "adult": false,
                          "popularity": 85.0,
                          "vote_count": 30000,
                          "vote_average": 8.4,
                          "video": false
                        }
                        """)));

    var result = provider.getMetadata(buildSearchResult("27205"), savedLibrary);

    assertThat(result).isPresent();
    assertThat(result.get().getExternalIds()).hasSize(1);
    assertThat(
            result.get().getExternalIds().stream()
                .anyMatch(id -> id.getExternalSourceType() == ExternalSourceType.TMDB))
        .isTrue();
  }

  @Test
  @DisplayName("Should skip content rating when no US certification exists")
  void shouldSkipContentRatingWhenNoUsCertificationExists() {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/27205"))
            .withQueryParam("append_to_response", equalTo("credits,releases"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 27205,
                          "title": "Inception",
                          "release_date": "2010-07-16",
                          "adult": false,
                          "popularity": 85.0,
                          "vote_count": 30000,
                          "vote_average": 8.4,
                          "video": false,
                          "releases": {
                            "countries": [
                              {
                                "certification": "15",
                                "iso_3166_1": "GB",
                                "primary": false,
                                "release_date": "2010-07-16"
                              }
                            ]
                          }
                        }
                        """)));

    var result = provider.getMetadata(buildSearchResult("27205"), savedLibrary);

    assertThat(result).isPresent();
    assertThat(result.get().getContentRating()).isNull();
  }

  @Test
  @DisplayName("Should handle null credits when credits absent from response")
  void shouldHandleNullCreditsWhenCreditsAbsentFromResponse() {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/27205"))
            .withQueryParam("append_to_response", equalTo("credits,releases"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 27205,
                          "title": "Inception",
                          "release_date": "2010-07-16",
                          "adult": false,
                          "popularity": 85.0,
                          "vote_count": 30000,
                          "vote_average": 8.4,
                          "video": false
                        }
                        """)));

    var result = provider.getMetadata(buildSearchResult("27205"), savedLibrary);

    assertThat(result).isPresent();
    assertThat(result.get().getCast()).isEmpty();
  }

  @Test
  @DisplayName("Should handle null releases when releases absent from response")
  void shouldHandleNullReleasesWhenReleasesAbsentFromResponse() {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/27205"))
            .withQueryParam("append_to_response", equalTo("credits,releases"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 27205,
                          "title": "Inception",
                          "release_date": "2010-07-16",
                          "adult": false,
                          "popularity": 85.0,
                          "vote_count": 30000,
                          "vote_average": 8.4,
                          "video": false
                        }
                        """)));

    var result = provider.getMetadata(buildSearchResult("27205"), savedLibrary);

    assertThat(result).isPresent();
    assertThat(result.get().getContentRating()).isNull();
  }

  @Test
  @DisplayName("Should handle null production companies when companies absent from response")
  void shouldHandleNullProductionCompaniesWhenCompaniesAbsentFromResponse() {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/27205"))
            .withQueryParam("append_to_response", equalTo("credits,releases"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 27205,
                          "title": "Inception",
                          "release_date": "2010-07-16",
                          "adult": false,
                          "popularity": 85.0,
                          "vote_count": 30000,
                          "vote_average": 8.4,
                          "video": false
                        }
                        """)));

    var result = provider.getMetadata(buildSearchResult("27205"), savedLibrary);

    assertThat(result).isPresent();
    assertThat(result.get().getStudios()).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when TMDB metadata API returns error")
  void shouldReturnEmptyWhenTmdbMetadataApiReturnsError() {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/27205"))
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

    var result = provider.getMetadata(buildSearchResult("27205"), savedLibrary);

    assertThat(result).isEmpty();
  }

  // --- Helpers ---

  private RemoteSearchResult buildSearchResult(String externalId) {
    return RemoteSearchResult.builder()
        .title("Inception")
        .externalId(externalId)
        .externalSourceType(ExternalSourceType.TMDB)
        .build();
  }

  private void stubFullMovieResponse() {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/27205"))
            .withQueryParam("append_to_response", equalTo("credits,releases"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 27205,
                          "title": "Inception",
                          "adult": false,
                          "overview": "A thief who steals corporate secrets through dream-sharing technology.",
                          "tagline": "Your mind is the scene of the crime.",
                          "release_date": "2010-07-16",
                          "runtime": 148,
                          "budget": 160000000,
                          "revenue": 836800000,
                          "popularity": 85.0,
                          "vote_average": 8.4,
                          "vote_count": 30000,
                          "video": false,
                          "status": "Released",
                          "imdb_id": "tt1375666",
                          "credits": {
                            "id": 27205,
                            "cast": [
                              {
                                "id": 6193,
                                "name": "Leonardo DiCaprio",
                                "character": "Cobb",
                                "order": 0,
                                "cast_id": 1,
                                "credit_id": "52fe4534c3a36847f80a7cd1",
                                "adult": false,
                                "gender": 2,
                                "popularity": 50.0
                              },
                              {
                                "id": 2524,
                                "name": "Tom Hardy",
                                "character": "Eames",
                                "order": 1,
                                "cast_id": 5,
                                "credit_id": "52fe4534c3a36847f80a7cdb",
                                "adult": false,
                                "gender": 2,
                                "popularity": 40.0
                              }
                            ],
                            "crew": [
                              {
                                "id": 525,
                                "name": "Christopher Nolan",
                                "job": "Director",
                                "department": "Directing",
                                "credit_id": "52fe4534c3a36847f80a7ce5",
                                "adult": false,
                                "gender": 2,
                                "popularity": 30.0
                              }
                            ]
                          },
                          "releases": {
                            "countries": [
                              {
                                "certification": "PG-13",
                                "iso_3166_1": "US",
                                "primary": true,
                                "release_date": "2010-07-16"
                              }
                            ]
                          },
                          "production_companies": [
                            {
                              "id": 923,
                              "name": "Legendary Entertainment",
                              "origin_country": "US"
                            }
                          ]
                        }
                        """)));
  }
}
