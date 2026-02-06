package com.streamarr.server.services.library;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
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
@DisplayName("Series Scanning Integration Tests")
public class SeriesScanningIT extends AbstractIntegrationTest {

  private static final WireMockServer wireMock =
      new WireMockServer(wireMockConfig().dynamicPort());

  @DynamicPropertySource
  static void configureWireMock(DynamicPropertyRegistry registry) {
    wireMock.start();
    registry.add("tmdb.api.base-url", wireMock::baseUrl);
    registry.add("tmdb.api.token", () -> "test-api-token");
  }

  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
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
    episodeRepository.deleteAll();
    seasonRepository.deleteAll();
    seriesRepository.deleteAll();
    libraryRepository.deleteAll();
  }

  @AfterAll
  static void tearDown() {
    wireMock.stop();
  }

  @Test
  @DisplayName("Should create series, season, and all episodes when processing new file")
  void shouldCreateSeriesSeasonAndAllEpisodesWhenProcessingNewFile() throws IOException {
    var library = createSeriesLibrary();
    var file = createSeriesFile("Breaking Bad", "Season 01", "breaking.bad.s01e01.mkv");

    stubTmdbSearch("Breaking Bad", "1396", "Breaking Bad");
    stubTmdbSeriesMetadata("1396", "Breaking Bad");
    stubTmdbSeasonDetails("1396", 1, buildSeason1Response());

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(seriesRepository.findAll()).hasSize(1);
    var series = seriesRepository.findAll().getFirst();
    assertThat(series.getTitle()).isEqualTo("Breaking Bad");

    assertThat(seasonRepository.findAll()).hasSize(1);
    var season = seasonRepository.findAll().getFirst();
    assertThat(season.getSeasonNumber()).isEqualTo(1);
    assertThat(season.getTitle()).isEqualTo("Season 1");

    var episodes = episodeRepository.findBySeasonId(season.getId());
    assertThat(episodes).hasSize(3);
    assertThat(episodes).extracting("episodeNumber").containsExactlyInAnyOrder(1, 2, 3);

    var mediaFile =
        mediaFileRepository.findFirstByFilepath(file.toAbsolutePath().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);

    var ep1 =
        episodeRepository.findBySeasonIdAndEpisodeNumber(season.getId(), 1);
    assertThat(ep1).isPresent();
    assertThat(mediaFile.get().getMediaId()).isEqualTo(ep1.get().getId());
  }

  @Test
  @DisplayName("Should reuse existing series and attach file to pre-created episode")
  void shouldReuseExistingSeriesAndAttachFileToPreCreatedEpisode() throws IOException {
    var library = createSeriesLibrary();
    var file1 = createSeriesFile("Breaking Bad", "Season 01", "breaking.bad.s01e01.mkv");
    var file2 = createSeriesFile("Breaking Bad", "Season 01", "breaking.bad.s01e02.mkv");

    stubTmdbSearch("Breaking Bad", "1396", "Breaking Bad");
    stubTmdbSeriesMetadata("1396");
    stubTmdbSeasonDetails("1396", 1, buildSeason1Response());

    libraryManagementService.processDiscoveredFile(library.getId(), file1);
    libraryManagementService.processDiscoveredFile(library.getId(), file2);

    assertThat(seriesRepository.findAll()).hasSize(1);
    assertThat(seasonRepository.findAll()).hasSize(1);

    var season = seasonRepository.findAll().getFirst();
    assertThat(episodeRepository.findBySeasonId(season.getId())).hasSize(3);

    var mediaFiles = mediaFileRepository.findByLibraryId(library.getId());
    assertThat(mediaFiles).hasSize(2);
    assertThat(mediaFiles).allMatch(mf -> mf.getStatus() == MediaFileStatus.MATCHED);

    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/tv/1396")));
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/tv/1396/season/1")));
  }

  @Test
  @DisplayName("Should create new season with episodes when file for unseen season appears")
  void shouldCreateNewSeasonWithEpisodesWhenFileForUnseenSeasonAppears() throws IOException {
    var library = createSeriesLibrary();
    var file1 = createSeriesFile("Breaking Bad", "Season 01", "breaking.bad.s01e01.mkv");
    var file2 = createSeriesFile("Breaking Bad", "Season 02", "breaking.bad.s02e01.mkv");

    stubTmdbSearch("Breaking Bad", "1396", "Breaking Bad");
    stubTmdbSeriesMetadata("1396");
    stubTmdbSeasonDetails("1396", 1, buildSeason1Response());
    stubTmdbSeasonDetails("1396", 2, buildSeason2Response());

    libraryManagementService.processDiscoveredFile(library.getId(), file1);
    libraryManagementService.processDiscoveredFile(library.getId(), file2);

    assertThat(seriesRepository.findAll()).hasSize(1);
    assertThat(seasonRepository.findAll()).hasSize(2);

    var seasons = seasonRepository.findAll();
    assertThat(seasons).extracting("seasonNumber").containsExactlyInAnyOrder(1, 2);

    var season1 = seasons.stream().filter(s -> s.getSeasonNumber() == 1).findFirst().orElseThrow();
    var season2 = seasons.stream().filter(s -> s.getSeasonNumber() == 2).findFirst().orElseThrow();
    assertThat(episodeRepository.findBySeasonId(season1.getId())).hasSize(3);
    assertThat(episodeRepository.findBySeasonId(season2.getId())).hasSize(2);
  }

  @Test
  @DisplayName("Should mark file as parsing failed when episode cannot be parsed")
  void shouldMarkFileAsParsingFailedWhenEpisodeCannotBeParsed() throws IOException {
    var library = createSeriesLibrary();
    var seriesDir = tempDir.resolve("NoPattern");
    Files.createDirectories(seriesDir);
    var file = seriesDir.resolve("random-noise.mkv");
    Files.createFile(file);

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    var mediaFile =
        mediaFileRepository.findFirstByFilepath(file.toAbsolutePath().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.METADATA_PARSING_FAILED);
  }

  @Test
  @DisplayName("Should mark file as search failed when TMDB returns no results")
  void shouldMarkFileAsSearchFailedWhenTmdbReturnsNoResults() throws IOException {
    var library = createSeriesLibrary();
    var file = createSeriesFile("Unknown Show", "Season 01", "unknown.show.s01e01.mkv");

    stubTmdbSearchEmpty("Unknown Show");

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    var mediaFile =
        mediaFileRepository.findFirstByFilepath(file.toAbsolutePath().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.METADATA_SEARCH_FAILED);
  }

  @Test
  @DisplayName("Should resolve series name from directory structure rather than filename")
  void shouldResolveSeriesNameFromDirectoryStructure() throws IOException {
    var library = createSeriesLibrary();
    var file = createSeriesFile("The Wire", "Season 01", "the.wire.s01e01.mkv");

    stubTmdbSearch("The Wire", "1438", "The Wire");
    stubTmdbSeriesMetadata("1438");
    stubTmdbSeasonDetails("1438", 1, buildMinimalSeasonResponse(1, 1));

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/search/tv"))
            .withQueryParam("query", equalTo("The Wire")));

    assertThat(seriesRepository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("Should use season folder number over filename season number")
  void shouldUseSeasonFolderNumberOverFilenameSeasonNumber() throws IOException {
    var library = createSeriesLibrary();
    var file = createSeriesFile("Show", "Season 03", "show.s02e01.mkv");

    stubTmdbSearch("Show", "9999", "Show");
    stubTmdbSeriesMetadata("9999");
    stubTmdbSeasonDetails("9999", 3, buildMinimalSeasonResponse(3, 1));

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(seasonRepository.findAll()).hasSize(1);
    assertThat(seasonRepository.findAll().getFirst().getSeasonNumber()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should default to season 1 when no season info available")
  void shouldDefaultToSeason1WhenNoSeasonInfoAvailable() throws IOException {
    var library = createSeriesLibrary();
    var seriesDir = tempDir.resolve("Firefly");
    Files.createDirectories(seriesDir);
    var file = seriesDir.resolve("firefly.s01e01.mkv");
    Files.createFile(file);

    stubTmdbSearch("Firefly", "1437", "Firefly");
    stubTmdbSeriesMetadata("1437");
    stubTmdbSeasonDetails("1437", 1, buildMinimalSeasonResponse(1, 1));

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(seasonRepository.findAll()).hasSize(1);
    assertThat(seasonRepository.findAll().getFirst().getSeasonNumber()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should create minimal episode when episode not in TMDB data")
  void shouldCreateMinimalEpisodeWhenEpisodeNotInTmdbData() throws IOException {
    var library = createSeriesLibrary();
    var file = createSeriesFile("Breaking Bad", "Season 01", "breaking.bad.s01e99.mkv");

    stubTmdbSearch("Breaking Bad", "1396", "Breaking Bad");
    stubTmdbSeriesMetadata("1396");
    stubTmdbSeasonDetails("1396", 1, buildSeason1Response());

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    var season = seasonRepository.findAll().getFirst();
    var episodes = episodeRepository.findBySeasonId(season.getId());
    assertThat(episodes).hasSize(4);

    var ep99 =
        episodeRepository.findBySeasonIdAndEpisodeNumber(season.getId(), 99);
    assertThat(ep99).isPresent();
    assertThat(ep99.get().getTitle()).isEqualTo("Episode 99");
    assertThat(ep99.get().getOverview()).isNull();

    var mediaFile =
        mediaFileRepository.findFirstByFilepath(file.toAbsolutePath().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);
    assertThat(mediaFile.get().getMediaId()).isEqualTo(ep99.get().getId());
  }

  // --- Helpers ---

  private Library createSeriesLibrary() {
    return libraryRepository.saveAndFlush(
        Library.builder()
            .name("TV Shows")
            .backend(com.streamarr.server.domain.LibraryBackend.LOCAL)
            .status(com.streamarr.server.domain.LibraryStatus.HEALTHY)
            .filepath(tempDir.toAbsolutePath().toString())
            .externalAgentStrategy(com.streamarr.server.domain.ExternalAgentStrategy.TMDB)
            .type(com.streamarr.server.domain.media.MediaType.SERIES)
            .build());
  }

  private Path createSeriesFile(String showDir, String seasonDir, String filename)
      throws IOException {
    var seasonPath = tempDir.resolve(showDir).resolve(seasonDir);
    Files.createDirectories(seasonPath);
    var file = seasonPath.resolve(filename);
    if (!Files.exists(file)) {
      Files.createFile(file);
    }
    return file;
  }

  private void stubTmdbSearch(String query, String tmdbId, String name) {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/tv"))
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
                              "name": "%s",
                              "original_name": "%s",
                              "first_air_date": "2008-01-20",
                              "popularity": 100.0,
                              "vote_count": 1000,
                              "vote_average": 8.5
                            }
                          ],
                          "total_results": 1,
                          "total_pages": 1
                        }
                        """
                            .formatted(tmdbId, name, name))));
  }

  private void stubTmdbSearchEmpty(String query) {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/tv"))
            .withQueryParam("query", equalTo(query))
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
  }

  private void stubTmdbSeriesMetadata(String tmdbId) {
    stubTmdbSeriesMetadata(tmdbId, "Test Series");
  }

  private void stubTmdbSeriesMetadata(String tmdbId, String name) {
    wireMock.stubFor(
        get(urlPathEqualTo("/tv/" + tmdbId))
            .withQueryParam(
                "append_to_response", equalTo("content_ratings,credits,external_ids"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": %s,
                          "name": "%s",
                          "original_name": "%s",
                          "first_air_date": "2008-01-20",
                          "overview": "A test series.",
                          "episode_run_time": [47],
                          "genres": [],
                          "production_companies": [],
                          "credits": {"id": %s, "cast": [], "crew": []},
                          "content_ratings": {"results": []},
                          "external_ids": {}
                        }
                        """
                            .formatted(tmdbId, name, name, tmdbId))));
  }

  private void stubTmdbSeasonDetails(String tmdbId, int seasonNumber, String body) {
    wireMock.stubFor(
        get(urlPathEqualTo("/tv/" + tmdbId + "/season/" + seasonNumber))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private String buildSeason1Response() {
    return """
        {
          "id": 3572,
          "name": "Season 1",
          "season_number": 1,
          "overview": "The first season.",
          "poster_path": "/poster1.jpg",
          "air_date": "2008-01-20",
          "episodes": [
            {"id": 62085, "episode_number": 1, "season_number": 1, "name": "Pilot", "overview": "Pilot episode.", "still_path": "/still1.jpg", "air_date": "2008-01-20", "runtime": 58},
            {"id": 62086, "episode_number": 2, "season_number": 1, "name": "Second Episode", "overview": "Second ep.", "still_path": "/still2.jpg", "air_date": "2008-01-27", "runtime": 48},
            {"id": 62087, "episode_number": 3, "season_number": 1, "name": "Third Episode", "overview": "Third ep.", "still_path": "/still3.jpg", "air_date": "2008-02-10", "runtime": 48}
          ]
        }
        """;
  }

  private String buildSeason2Response() {
    return """
        {
          "id": 3573,
          "name": "Season 2",
          "season_number": 2,
          "overview": "The second season.",
          "poster_path": "/poster2.jpg",
          "air_date": "2009-03-08",
          "episodes": [
            {"id": 62092, "episode_number": 1, "season_number": 2, "name": "Seven Thirty-Seven", "overview": "S2E1.", "still_path": "/s2still1.jpg", "air_date": "2009-03-08", "runtime": 47},
            {"id": 62093, "episode_number": 2, "season_number": 2, "name": "Grilled", "overview": "S2E2.", "still_path": "/s2still2.jpg", "air_date": "2009-03-15", "runtime": 48}
          ]
        }
        """;
  }

  private String buildMinimalSeasonResponse(int seasonNumber, int episodeCount) {
    var episodesBuilder = new StringBuilder("[");
    for (int i = 1; i <= episodeCount; i++) {
      if (i > 1) {
        episodesBuilder.append(",");
      }
      episodesBuilder.append(
          """
          {"id": %d, "episode_number": %d, "season_number": %d, "name": "Episode %d", "overview": "Episode %d.", "runtime": 45}
          """
              .formatted(70000 + i, i, seasonNumber, i, i));
    }
    episodesBuilder.append("]");

    return """
        {
          "id": %d,
          "name": "Season %d",
          "season_number": %d,
          "overview": "Season %d overview.",
          "episodes": %s
        }
        """
        .formatted(5000 + seasonNumber, seasonNumber, seasonNumber, seasonNumber, episodesBuilder.toString());
  }
}
