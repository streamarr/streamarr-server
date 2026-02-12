package com.streamarr.server.services.library;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

@Tag("IntegrationTest")
@DisplayName("Movie Scanning Integration Tests")
public class MovieScanningIT extends AbstractIntegrationTest {

  private static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

  @DynamicPropertySource
  static void configureWireMock(DynamicPropertyRegistry registry) {
    wireMock.start();
    registry.add("tmdb.api.base-url", wireMock::baseUrl);
    registry.add("tmdb.api.token", () -> "test-api-token");
  }

  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;

  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean FfprobeService ffprobeService;
  @TestBean SegmentStore segmentStore;

  private static final FakeTranscodeExecutor FAKE_EXECUTOR = new FakeTranscodeExecutor();
  private static final FakeFfprobeService FAKE_FFPROBE = new FakeFfprobeService();
  private static final FakeSegmentStore FAKE_SEGMENT_STORE = new FakeSegmentStore();

  static TranscodeExecutor transcodeExecutor() {
    return FAKE_EXECUTOR;
  }

  static FfprobeService ffprobeService() {
    return FAKE_FFPROBE;
  }

  static SegmentStore segmentStore() {
    return FAKE_SEGMENT_STORE;
  }

  @TempDir Path tempDir;

  @BeforeEach
  void cleanupDatabase() {
    wireMock.resetAll();
    mediaFileRepository.deleteAll();
    movieRepository.deleteAll();
    libraryRepository.deleteAll();
  }

  @AfterAll
  static void tearDown() {
    wireMock.stop();
  }

  @Test
  @DisplayName("Should fall back to folder name when filename lacks year")
  void shouldFallBackToFolderNameWhenFilenameLacksYear() throws IOException {
    var library = createMovieLibrary();
    var file = createMovieFile("Inception (2010)", "movie.mkv");

    stubTmdbMovieSearch("Inception", "27205", "Inception", "2010-07-16");
    stubTmdbMovieMetadata("27205", "Inception");

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(movieRepository.findAll()).hasSize(1);
    var movie = movieRepository.findAll().getFirst();
    assertThat(movie.getTitle()).isEqualTo("Inception");

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);
  }

  @Test
  @DisplayName("Should parse movie directly from filename when year is present")
  void shouldParseMovieDirectlyFromFilenameWhenYearIsPresent() throws IOException {
    var library = createMovieLibrary();
    var file = createMovieFile("Movies", "Inception (2010).mkv");

    stubTmdbMovieSearch("Inception", "27205", "Inception", "2010-07-16");
    stubTmdbMovieMetadata("27205", "Inception");

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(movieRepository.findAll()).hasSize(1);
    assertThat(movieRepository.findAll().getFirst().getTitle()).isEqualTo("Inception");
  }

  // --- Helpers ---

  private Library createMovieLibrary() {
    return libraryRepository.saveAndFlush(
        Library.builder()
            .name("Movies")
            .backend(com.streamarr.server.domain.LibraryBackend.LOCAL)
            .status(com.streamarr.server.domain.LibraryStatus.HEALTHY)
            .filepath(tempDir.toAbsolutePath().toString())
            .externalAgentStrategy(com.streamarr.server.domain.ExternalAgentStrategy.TMDB)
            .type(com.streamarr.server.domain.media.MediaType.MOVIE)
            .build());
  }

  private Path createMovieFile(String directory, String filename) throws IOException {
    var dir = tempDir.resolve(directory);
    Files.createDirectories(dir);
    var file = dir.resolve(filename);
    if (!Files.exists(file)) {
      Files.createFile(file);
    }
    return file;
  }

  private void stubTmdbMovieSearch(String query, String tmdbId, String title, String releaseDate) {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .withQueryParam("query", equalTo(query))
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
                              "id": %s,
                              "title": "%s",
                              "original_title": "%s",
                              "release_date": "%s",
                              "popularity": 100.0,
                              "vote_count": 1000,
                              "vote_average": 8.5
                            }
                          ],
                          "total_results": 1,
                          "total_pages": 1
                        }
                        """
                            .formatted(tmdbId, title, title, releaseDate))));
  }

  private void stubTmdbMovieMetadata(String tmdbId, String title) {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/" + tmdbId))
            .withQueryParam("append_to_response", equalTo("credits,release_dates"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": %s,
                          "title": "%s",
                          "original_title": "%s",
                          "release_date": "2010-07-16",
                          "overview": "A thief who steals corporate secrets through dream-sharing technology.",
                          "runtime": 148,
                          "imdb_id": "tt1375666",
                          "genres": [{"id": 28, "name": "Action"}],
                          "production_companies": [],
                          "credits": {"id": %s, "cast": [], "crew": []},
                          "release_dates": {"results": []}
                        }
                        """
                            .formatted(tmdbId, title, title, tmdbId))));
  }
}
