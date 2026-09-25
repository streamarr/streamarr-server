package com.streamarr.server.services.library;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Isolated
@Tag("IntegrationTest")
@DisplayName("Movie Scanning Integration Tests")
class MovieScanningIT extends AbstractScanningIntegrationTest {

  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @TempDir Path tempDir;

  @BeforeEach
  void cleanupDatabase() {
    wireMock.resetAll();
    mediaFileRepository.deleteAll();
    movieRepository.deleteAll();
    libraryRepository.deleteAll();
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
  }

  @Test
  @DisplayName("Should match media file when folder name fallback succeeds")
  void shouldMatchMediaFileWhenFolderNameFallbackSucceeds() throws IOException {
    var library = createMovieLibrary();
    var file = createMovieFile("Inception (2010)", "movie.mkv");

    stubTmdbMovieSearch("Inception", "27205", "Inception", "2010-07-16");
    stubTmdbMovieMetadata("27205", "Inception");

    libraryManagementService.processDiscoveredFile(library.getId(), file);

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

  @Test
  @DisplayName("Should become unhealthy when the database cannot save an item error during a scan")
  void shouldBecomeUnhealthyWhenTheDatabaseCannotSaveAnItemErrorDuringAScan() throws IOException {
    var library = createMovieLibrary();
    createMovieFile("Inception (2010)", "Inception (2010).mkv");
    stubTmdbMovieSearch("Inception", "27205", "Inception", "2010-07-16");
    stubTmdbMovieMetadata("27205", "Inception");

    rejectItemErrorsWhile(() -> libraryManagementService.scanLibrary(library.getId()));

    assertThat(libraryRepository.findById(library.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.UNHEALTHY);
  }

  // --- Helpers ---

  // The movie has no poster or backdrop, so the scan must record them as unavailable.
  private void rejectItemErrorsWhile(Runnable action) {
    jdbcTemplate.execute(
        """
        CREATE FUNCTION reject_item_error() RETURNS trigger AS $$
        BEGIN
          RAISE EXCEPTION 'simulated item error write failure';
        END
        $$ LANGUAGE plpgsql
        """);
    jdbcTemplate.execute(
        """
        CREATE TRIGGER reject_item_error BEFORE INSERT OR UPDATE ON item_result
        FOR EACH ROW WHEN (NEW.outcome <> 'SUCCEEDED') EXECUTE FUNCTION reject_item_error()
        """);
    try {
      action.run();
    } finally {
      jdbcTemplate.execute("DROP TRIGGER reject_item_error ON item_result");
      jdbcTemplate.execute("DROP FUNCTION reject_item_error()");
    }
  }

  private Library createMovieLibrary() {
    return libraryRepository.saveAndFlush(
        Library.builder()
            .name("Movies")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepathUri(FilepathCodec.encode(tempDir))
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
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
