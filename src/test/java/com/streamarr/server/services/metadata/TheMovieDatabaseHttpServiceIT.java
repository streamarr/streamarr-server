package com.streamarr.server.services.metadata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("IntegrationTest")
@DisplayName("TMDB HTTP Service Integration Tests")
class TheMovieDatabaseHttpServiceIT extends AbstractIntegrationTest {

  private static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

  @DynamicPropertySource
  static void configureWireMock(DynamicPropertyRegistry registry) {
    wireMock.start();

    registry.add("tmdb.api.base-url", wireMock::baseUrl);
    registry.add("tmdb.api.token", () -> "test-api-token");
  }

  @Autowired private TheMovieDatabaseHttpService service;

  @BeforeEach
  void resetStubs() {
    wireMock.resetAll();
  }

  @AfterAll
  static void tearDown() {
    wireMock.stop();
  }

  @Test
  @DisplayName("Should return search results when searching with title and year")
  void shouldReturnSearchResultsWhenSearchingWithTitleAndYear() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .withQueryParam("query", equalTo("Inception"))
            .withQueryParam("year", equalTo("2010"))
            .withHeader("Authorization", equalTo("Bearer test-api-token"))
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

    var parserResult = VideoFileParserResult.builder().title("Inception").year("2010").build();

    var results = service.searchForMovie(parserResult);

    assertThat(results.getResults()).hasSize(1);
    assertThat(results.getResults().getFirst().getTitle()).isEqualTo("Inception");
    assertThat(results.getResults().getFirst().getId()).isEqualTo(27205);
    assertThat(results.getTotalResults()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should search successfully when year is blank")
  void shouldReturnSearchResultsWhenYearIsBlank() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .withQueryParam("query", equalTo("About Time"))
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
                          "id": 122906,
                          "title": "About Time",
                          "release_date": "2013-09-04",
                          "adult": false,
                          "popularity": 50.0,
                          "vote_count": 5000,
                          "vote_average": 7.8,
                          "video": false
                        }
                      ],
                      "total_results": 1,
                      "total_pages": 1
                    }
                    """)));

    var parserResult = VideoFileParserResult.builder().title("About Time").year("").build();

    var results = service.searchForMovie(parserResult);

    assertThat(results.getResults()).hasSize(1);
    assertThat(results.getResults().getFirst().getTitle()).isEqualTo("About Time");
  }

  @Test
  @DisplayName("Should return movie metadata with nested credits and releases")
  void shouldReturnMovieWithNestedDataWhenGettingMetadataById() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/27205"))
            .withQueryParam("append_to_response", equalTo("credits,release_dates"))
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
                      "release_dates": {
                        "results": [
                          {
                            "iso_3166_1": "US",
                            "release_dates": [
                              {
                                "certification": "PG-13",
                                "release_date": "2010-07-16T00:00:00.000Z",
                                "type": 3,
                                "note": ""
                              }
                            ]
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

    var movie = service.getMovieMetadata("27205");

    assertThat(movie.getTitle()).isEqualTo("Inception");
    assertThat(movie.getRuntime()).isEqualTo(148);
    assertThat(movie.getImdbId()).isEqualTo("tt1375666");
    assertThat(movie.getTagline()).isEqualTo("Your mind is the scene of the crime.");
    assertThat(movie.getCredits().getCast()).hasSize(1);
    assertThat(movie.getCredits().getCast().getFirst().getName()).isEqualTo("Leonardo DiCaprio");
    assertThat(movie.getCredits().getCrew()).hasSize(1);
    assertThat(movie.getCredits().getCrew().getFirst().getJob()).isEqualTo("Director");
    assertThat(movie.getReleaseDates().getResults()).hasSize(1);
    assertThat(movie.getReleaseDates().getResults().getFirst().getIso31661()).isEqualTo("US");
    assertThat(movie.getReleaseDates().getResults().getFirst().getReleaseDates()).hasSize(1);
    assertThat(
            movie.getReleaseDates().getResults().getFirst().getReleaseDates().getFirst()
                .getCertification())
        .isEqualTo("PG-13");
    assertThat(movie.getProductionCompanies()).hasSize(1);
    assertThat(movie.getProductionCompanies().getFirst().getName())
        .isEqualTo("Legendary Entertainment");
  }

  @Test
  @DisplayName("Should return movie credits from standalone credits endpoint")
  void shouldReturnCreditsWhenGettingMovieCredits() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/27205/credits"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                    {
                      "id": 27205,
                      "cast": [
                        {
                          "id": 6193,
                          "name": "Leonardo DiCaprio",
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
                          "order": 2,
                          "cast_id": 5,
                          "credit_id": "52fe4534c3a36847f80a7cdb",
                          "adult": false,
                          "gender": 2,
                          "popularity": 40.0
                        }
                      ],
                      "crew": []
                    }
                    """)));

    var credits = service.getMovieCreditsMetadata("27205");

    assertThat(credits.getCast()).hasSize(2);
    assertThat(credits.getCast().get(0).getName()).isEqualTo("Leonardo DiCaprio");
    assertThat(credits.getCast().get(1).getName()).isEqualTo("Tom Hardy");
  }

  @Test
  @DisplayName("Should deserialize collection when response includes belongs_to_collection")
  void shouldDeserializeCollectionWhenResponseIncludesBelongsToCollection() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/120"))
            .withQueryParam("append_to_response", equalTo("credits,release_dates"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                    {
                      "id": 120,
                      "title": "The Lord of the Rings: The Fellowship of the Ring",
                      "adult": false,
                      "release_date": "2001-12-18",
                      "popularity": 90.0,
                      "vote_count": 20000,
                      "vote_average": 8.4,
                      "video": false,
                      "belongs_to_collection": {
                        "id": 119,
                        "name": "The Lord of the Rings Collection",
                        "poster_path": "/poster.jpg",
                        "backdrop_path": "/backdrop.jpg"
                      }
                    }
                    """)));

    var movie = service.getMovieMetadata("120");

    assertThat(movie.getBelongsToCollection()).isNotNull();
    assertThat(movie.getBelongsToCollection().getId()).isEqualTo(119);
    assertThat(movie.getBelongsToCollection().getName())
        .isEqualTo("The Lord of the Rings Collection");
    assertThat(movie.getBelongsToCollection().getPosterPath()).isEqualTo("/poster.jpg");
    assertThat(movie.getBelongsToCollection().getBackdropPath()).isEqualTo("/backdrop.jpg");
  }

  @Test
  @DisplayName("Should throw IOException with TMDB error message when API returns error status")
  void shouldThrowIOExceptionWithMessageWhenApiReturnsError() {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                    {
                      "status_message": "Invalid API key: You must be granted a valid key.",
                      "success": false,
                      "status_code": 7
                    }
                    """)));

    var parserResult = VideoFileParserResult.builder().title("Anything").build();

    assertThatThrownBy(() -> service.searchForMovie(parserResult))
        .isInstanceOf(IOException.class)
        .hasMessage("Invalid API key: You must be granted a valid key.");
  }

  @Test
  @DisplayName("Should retry and succeed when temporarily rate limited")
  void shouldRetryAndSucceedWhenTemporarilyRateLimited() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .inScenario("Rate Limit Recovery")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("Recovered"));

    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .inScenario("Rate Limit Recovery")
            .whenScenarioStateIs("Recovered")
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
                          "id": 1,
                          "title": "Recovered",
                          "adult": false,
                          "popularity": 1.0,
                          "vote_count": 0,
                          "vote_average": 0,
                          "video": false
                        }
                      ],
                      "total_results": 1,
                      "total_pages": 1
                    }
                    """)));

    var parserResult = VideoFileParserResult.builder().title("Test").build();

    var results = service.searchForMovie(parserResult);

    assertThat(results.getResults()).hasSize(1);
    assertThat(results.getResults().getFirst().getTitle()).isEqualTo("Recovered");
  }

  @Test
  @DisplayName("Should throw IOException when rate limit persists after max retries")
  void shouldThrowIOExceptionWhenRateLimitPersistsAfterMaxRetries() {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")));

    var parserResult = VideoFileParserResult.builder().title("Test").build();

    assertThatThrownBy(() -> service.searchForMovie(parserResult))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("rate limit exceeded");
  }
}
