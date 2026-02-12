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
class SeriesScanningIT extends AbstractIntegrationTest {

  private static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

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
    assertThat(episodes).hasSize(7);
    assertThat(episodes).extracting("episodeNumber").containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7);

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);

    var ep1 = episodeRepository.findBySeasonIdAndEpisodeNumber(season.getId(), 1);
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
    assertThat(episodeRepository.findBySeasonId(season.getId())).hasSize(7);

    var mediaFiles = mediaFileRepository.findByLibraryId(library.getId());
    assertThat(mediaFiles).hasSize(2).allMatch(mf -> mf.getStatus() == MediaFileStatus.MATCHED);

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
    assertThat(episodeRepository.findBySeasonId(season1.getId())).hasSize(7);
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
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
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
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
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
        getRequestedFor(urlPathEqualTo("/search/tv")).withQueryParam("query", equalTo("The Wire")));

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
  @DisplayName("Should match series when folder name contains external ID tag")
  void shouldMatchSeriesWhenFolderNameContainsExternalIdTag() throws IOException {
    var library = createSeriesLibrary();
    var file = createSeriesFile("Hilda (2018) [imdb-tt6385540]", "Season 01", "hilda.s01e06.mkv");

    stubTmdbSearch("Hilda", "68488", "Hilda");
    stubTmdbSeriesMetadata("68488", "Hilda");
    stubTmdbSeasonDetails("68488", 1, buildMinimalSeasonResponse(1, 6));

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(seriesRepository.findAll()).hasSize(1);
    var series = seriesRepository.findAll().getFirst();
    assertThat(series.getTitle()).isEqualTo("Hilda");

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);
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
    assertThat(episodes).hasSize(8);

    var ep99 = episodeRepository.findBySeasonIdAndEpisodeNumber(season.getId(), 99);
    assertThat(ep99).isPresent();
    assertThat(ep99.get().getTitle()).isEqualTo("Episode 99");
    assertThat(ep99.get().getOverview()).isNull();

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);
    assertThat(mediaFile.get().getMediaId()).isEqualTo(ep99.get().getId());
  }

  @Test
  @DisplayName("Should match series when folder name contains TVDB external ID tag")
  void shouldMatchSeriesWhenFolderNameContainsTvdbExternalIdTag() throws IOException {
    var library = createSeriesLibrary();
    var file = createSeriesFile("Hilda (2018) [tvdb-349517]", "Season 01", "hilda.s01e01.mkv");

    stubTmdbFindTvResult("349517", "tvdb_id", "68488", "Hilda");
    stubTmdbSeriesMetadata("68488", "Hilda");
    stubTmdbSeasonDetails("68488", 1, buildMinimalSeasonResponse(1, 1));

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(seriesRepository.findAll()).hasSize(1);
    var series = seriesRepository.findAll().getFirst();
    assertThat(series.getTitle()).isEqualTo("Hilda");

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/find/349517"))
            .withQueryParam("external_source", equalTo("tvdb_id")));
  }

  @Test
  @DisplayName("Should resolve year-based season folder to sequential TMDB season number")
  void shouldResolveYearBasedSeasonToSequentialTmdbSeasonNumber() throws IOException {
    var library = createSeriesLibrary();
    var file = createSeriesFile("MythBusters", "Season 2012", "mythbusters.s2012e01.mkv");

    stubTmdbSearch("MythBusters", "1428", "MythBusters");
    stubTmdbSeriesMetadataWithSeasons(
        "1428",
        "MythBusters",
        """
        [
          {"id": 100, "season_number": 1, "name": "Season 1", "air_date": "2003-01-23", "episode_count": 14},
          {"id": 109, "season_number": 10, "name": "Season 10", "air_date": "2012-03-25", "episode_count": 18},
          {"id": 110, "season_number": 11, "name": "Season 11", "air_date": "2013-01-16", "episode_count": 18}
        ]
        """);
    stubTmdbSeasonDetails("1428", 10, buildMinimalSeasonResponse(10, 1));

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(seriesRepository.findAll()).hasSize(1);
    assertThat(seasonRepository.findAll()).hasSize(1);
    var season = seasonRepository.findAll().getFirst();
    assertThat(season.getSeasonNumber()).isEqualTo(10);

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);
  }

  @Test
  @DisplayName(
      "Should reuse cached season summaries when processing second file in same year-based season")
  void shouldReuseCachedSeasonSummariesForSecondFileInSameYearBasedSeason() throws IOException {
    var library = createSeriesLibrary();
    var file1 = createSeriesFile("MythBusters", "Season 2012", "mythbusters.s2012e01.mkv");
    var file2 = createSeriesFile("MythBusters", "Season 2012", "mythbusters.s2012e03.mkv");

    stubTmdbSearch("MythBusters", "1428", "MythBusters");
    stubTmdbSeriesMetadataWithSeasons(
        "1428",
        "MythBusters",
        """
        [
          {"id": 100, "season_number": 1, "name": "Season 1", "air_date": "2003-01-23", "episode_count": 14},
          {"id": 109, "season_number": 10, "name": "Season 10", "air_date": "2012-03-25", "episode_count": 18},
          {"id": 110, "season_number": 11, "name": "Season 11", "air_date": "2013-01-16", "episode_count": 18}
        ]
        """);
    stubTmdbSeasonDetails("1428", 10, buildMinimalSeasonResponse(10, 3));

    libraryManagementService.processDiscoveredFile(library.getId(), file1);
    libraryManagementService.processDiscoveredFile(library.getId(), file2);

    assertThat(seriesRepository.findAll()).hasSize(1);
    assertThat(seasonRepository.findAll()).hasSize(1);
    assertThat(seasonRepository.findAll().getFirst().getSeasonNumber()).isEqualTo(10);

    var mediaFiles = mediaFileRepository.findByLibraryId(library.getId());
    assertThat(mediaFiles).hasSize(2);
    assertThat(mediaFiles).allMatch(mf -> mf.getStatus() == MediaFileStatus.MATCHED);

    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/tv/1428")));
  }

  @Test
  @DisplayName("Should not attempt season fetch when year-based season cannot be resolved")
  void shouldNotAttemptSeasonFetchWhenYearBasedSeasonCannotBeResolved() throws IOException {
    var library = createSeriesLibrary();
    var file = createSeriesFile("SomeShow", "Season 2050", "someshow.s2050e01.mkv");

    stubTmdbSearch("SomeShow", "9999", "SomeShow");
    stubTmdbSeriesMetadataWithSeasons(
        "9999",
        "SomeShow",
        """
        [
          {"id": 200, "season_number": 1, "name": "Season 1", "air_date": "2020-01-15", "episode_count": 10}
        ]
        """);

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isNotEqualTo(MediaFileStatus.MATCHED);

    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/tv/9999/season/2050")));
  }

  @Test
  @DisplayName("Should not trigger year-based resolution for normal sequential season numbers")
  void shouldNotTriggerYearBasedResolutionForNormalSequentialSeasonNumbers() throws IOException {
    var library = createSeriesLibrary();
    var file = createSeriesFile("Show", "Season 01", "show.s01e01.mkv");

    stubTmdbSearch("Show", "5555", "Show");
    stubTmdbSeriesMetadataWithSeasons(
        "5555",
        "Show",
        """
        [
          {"id": 300, "season_number": 1, "name": "Season 1", "air_date": "2020-01-15", "episode_count": 10}
        ]
        """);
    stubTmdbSeasonDetails("5555", 1, buildMinimalSeasonResponse(1, 1));

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(seasonRepository.findAll()).hasSize(1);
    assertThat(seasonRepository.findAll().getFirst().getSeasonNumber()).isEqualTo(1);

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);

    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/tv/5555")));
  }

  @Test
  @DisplayName("Should match date-only episode via TMDB air date lookup")
  void shouldMatchDateOnlyEpisodeViaTmdbAirDateLookup() throws IOException {
    var library = createSeriesLibrary();
    var showDir = tempDir.resolve("Jeopardy!");
    Files.createDirectories(showDir);
    var file = showDir.resolve("Jeopardy! - 2025-11-25.mkv");
    Files.createFile(file);

    stubTmdbSearch("Jeopardy!", "2141", "Jeopardy!");
    stubTmdbSeriesMetadataWithSeasons(
        "2141",
        "Jeopardy!",
        """
        [
          {"id": 500, "season_number": 42, "name": "Season 42", "air_date": "2025-09-08", "episode_count": 230}
        ]
        """);
    stubTmdbSeasonDetails(
        "2141",
        42,
        """
        {
          "id": 500,
          "name": "Season 42",
          "season_number": 42,
          "overview": "Season 42 of Jeopardy!",
          "episodes": [
            {"id": 80001, "episode_number": 55, "season_number": 42, "name": "Show #9955", "overview": "Episode 55.", "air_date": "2025-11-24", "runtime": 30},
            {"id": 80002, "episode_number": 56, "season_number": 42, "name": "Show #9956", "overview": "Episode 56.", "air_date": "2025-11-25", "runtime": 30},
            {"id": 80003, "episode_number": 57, "season_number": 42, "name": "Show #9957", "overview": "Episode 57.", "air_date": "2025-11-26", "runtime": 30}
          ]
        }
        """);

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(seriesRepository.findAll()).hasSize(1);
    assertThat(seasonRepository.findAll()).hasSize(1);

    var season = seasonRepository.findAll().getFirst();
    assertThat(season.getSeasonNumber()).isEqualTo(42);

    var ep56 = episodeRepository.findBySeasonIdAndEpisodeNumber(season.getId(), 56);
    assertThat(ep56).isPresent();

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);
    assertThat(mediaFile.get().getMediaId()).isEqualTo(ep56.get().getId());
  }

  @Test
  @DisplayName("Should mark date-only file as search failed when no air date matches")
  void shouldMarkDateOnlyFileAsSearchFailedWhenNoAirDateMatches() throws IOException {
    var library = createSeriesLibrary();
    var showDir = tempDir.resolve("Daily Show");
    Files.createDirectories(showDir);
    var file = showDir.resolve("Daily Show - 2025-12-25.mkv");
    Files.createFile(file);

    stubTmdbSearch("Daily Show", "3000", "Daily Show");
    stubTmdbSeriesMetadataWithSeasons(
        "3000",
        "Daily Show",
        """
        [
          {"id": 600, "season_number": 1, "name": "Season 1", "air_date": "2025-01-01", "episode_count": 10}
        ]
        """);
    stubTmdbSeasonDetails(
        "3000",
        1,
        """
        {
          "id": 600,
          "name": "Season 1",
          "season_number": 1,
          "overview": "Season 1.",
          "episodes": [
            {"id": 90001, "episode_number": 1, "season_number": 1, "name": "Ep 1", "overview": ".", "air_date": "2025-01-06", "runtime": 30}
          ]
        }
        """);

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.METADATA_SEARCH_FAILED);
  }

  @Test
  @DisplayName("Should select correct series when TMDB returns multiple results")
  void shouldSelectCorrectSeriesWhenTmdbReturnsMultipleResults() throws IOException {
    var library = createSeriesLibrary();
    var file = createSeriesFile("Breaking Bad", "Season 01", "breaking.bad.s01e01.mkv");

    stubTmdbSearchMultipleResults(
        "Breaking Bad",
        """
        [
          {
            "id": 9999,
            "name": "Breaking Point",
            "original_name": "Breaking Point",
            "first_air_date": "2008-03-15",
            "popularity": 5.0,
            "vote_count": 100,
            "vote_average": 6.0
          },
          {
            "id": 1396,
            "name": "Breaking Bad",
            "original_name": "Breaking Bad",
            "first_air_date": "2008-01-20",
            "popularity": 500.0,
            "vote_count": 10000,
            "vote_average": 8.9
          }
        ]
        """);
    stubTmdbSeriesMetadata("1396", "Breaking Bad");
    stubTmdbSeasonDetails("1396", 1, buildMinimalSeasonResponse(1, 1));

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(seriesRepository.findAll()).hasSize(1);
    var series = seriesRepository.findAll().getFirst();
    assertThat(series.getTitle()).isEqualTo("Breaking Bad");

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);

    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/tv/1396")));
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/tv/9999")));
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

  private void stubTmdbFindTvResult(
      String externalId, String externalSource, String tmdbId, String name) {
    wireMock.stubFor(
        get(urlPathEqualTo("/find/" + externalId))
            .withQueryParam("external_source", equalTo(externalSource))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "tv_results": [
                            {
                              "id": %s,
                              "name": "%s",
                              "original_name": "%s",
                              "first_air_date": "2018-09-21",
                              "popularity": 50.0,
                              "vote_count": 500,
                              "vote_average": 8.0
                            }
                          ]
                        }
                        """
                            .formatted(tmdbId, name, name))));
  }

  private void stubTmdbSearchMultipleResults(String query, String resultsJson) {
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
                          "results": %s,
                          "total_results": 2,
                          "total_pages": 1
                        }
                        """
                            .formatted(resultsJson))));
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
            .withQueryParam("append_to_response", equalTo("content_ratings,credits,external_ids"))
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
                          "overview": "Walter White, a New Mexico chemistry teacher, is diagnosed with Stage III cancer and given a prognosis of only two years left to live.",
                          "episode_run_time": [],
                          "genres": [
                            {"id": 18, "name": "Drama"},
                            {"id": 80, "name": "Crime"}
                          ],
                          "production_companies": [
                            {"id": 11073, "logo_path": "/wHs44fktdoj6c378ZbSWfzKsM2Z.png", "name": "Sony Pictures Television", "origin_country": "US"},
                            {"id": 33742, "logo_path": "/2jdh2sEa0R6y6uT0F7g0IgA2WO8.png", "name": "High Bridge Productions", "origin_country": "US"}
                          ],
                          "credits": {
                            "id": %s,
                            "cast": [
                              {"id": 17419, "adult": false, "gender": 2, "name": "Bryan Cranston", "original_name": "Bryan Cranston", "popularity": 7.13, "profile_path": "/npIIZJGSrcJIJ6yHdmbqO6Jzo5I.jpg", "character": "Walter White", "known_for_department": "Acting", "order": 0},
                              {"id": 84497, "adult": false, "gender": 2, "name": "Aaron Paul", "original_name": "Aaron Paul", "popularity": 3.69, "profile_path": "/8Ac9uuoYwZoYVAIJfRLzzLsGGJn.jpg", "character": "Jesse Pinkman", "known_for_department": "Acting", "order": 1}
                            ],
                            "crew": [
                              {"id": 66633, "adult": false, "gender": 2, "name": "Vince Gilligan", "original_name": "Vince Gilligan", "popularity": 2.85, "department": "Production", "job": "Executive Producer", "known_for_department": "Writing"}
                            ]
                          },
                          "content_ratings": {
                            "results": [
                              {"iso_3166_1": "US", "rating": "TV-MA", "descriptors": []}
                            ]
                          },
                          "external_ids": {
                            "imdb_id": "tt0903747",
                            "tvdb_id": 81189
                          }
                        }
                        """
                            .formatted(tmdbId, name, name, tmdbId))));
  }

  private void stubTmdbSeriesMetadataWithSeasons(String tmdbId, String name, String seasonsJson) {
    wireMock.stubFor(
        get(urlPathEqualTo("/tv/" + tmdbId))
            .withQueryParam("append_to_response", equalTo("content_ratings,credits,external_ids"))
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
                          "first_air_date": "2003-01-23",
                          "overview": "Test overview.",
                          "episode_run_time": [],
                          "genres": [],
                          "production_companies": [],
                          "seasons": %s,
                          "credits": {"id": %s, "cast": [], "crew": []},
                          "content_ratings": {"results": []},
                          "external_ids": {"imdb_id": "tt0383126", "tvdb_id": 73388}
                        }
                        """
                            .formatted(tmdbId, name, name, seasonsJson, tmdbId))));
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
          "overview": "High school chemistry teacher Walter White's life is suddenly transformed by a dire medical diagnosis. Street-savvy former student Jesse Pinkman \\"teaches\\" Walter a new trade.",
          "poster_path": "/1BP4xYv9ZG4ZVHkL7ocOziBbSYH.jpg",
          "air_date": "2008-01-20",
          "episodes": [
            {"id": 62085, "episode_number": 1, "season_number": 1, "name": "Pilot", "overview": "When an unassuming high school chemistry teacher discovers he has a rare form of lung cancer, he decides to team up with a former student and create a top of the line crystal meth in a used RV, to provide for his family once he is gone.", "still_path": "/88Z0fMP8a88EpQWMCs1593G0ngu.jpg", "air_date": "2008-01-20", "runtime": 59},
            {"id": 62086, "episode_number": 2, "season_number": 1, "name": "Cat's in the Bag...", "overview": "Walt and Jesse attempt to tie up loose ends.", "still_path": "/AbMoecO0ZZio0LcgeLxlzdyGs6X.jpg", "air_date": "2008-01-27", "runtime": 49},
            {"id": 62087, "episode_number": 3, "season_number": 1, "name": "...And the Bag's in the River", "overview": "Walter fights with Jesse over his drug use.", "still_path": "/2kBeBlxGqBOdWlKwzAxiwkfU5on.jpg", "air_date": "2008-02-10", "runtime": 49},
            {"id": 62088, "episode_number": 4, "season_number": 1, "name": "Cancer Man", "overview": "Walter finally tells his family that he has been stricken with cancer.", "still_path": "/2UbRgW6apE4XPzhHPA726wUFyaR.jpg", "air_date": "2008-02-17", "runtime": 49},
            {"id": 62089, "episode_number": 5, "season_number": 1, "name": "Gray Matter", "overview": "Walter and Skyler attend a former colleague's party.", "still_path": "/82G3wZgEvZLKcte6yoZJahUWBtx.jpg", "air_date": "2008-02-24", "runtime": 49},
            {"id": 62090, "episode_number": 6, "season_number": 1, "name": "Crazy Handful of Nothin'", "overview": "The side effects of chemo begin to plague Walt.", "still_path": "/rCCLuycNPL30W3BtuB8HafxEMYz.jpg", "air_date": "2008-03-02", "runtime": 49},
            {"id": 62091, "episode_number": 7, "season_number": 1, "name": "A No Rough Stuff Type Deal", "overview": "Walter accepts his new identity as a drug dealer after a PTA meeting.", "still_path": "/1dgFAsajUpUT7DLXgAxHb9GyXHH.jpg", "air_date": "2008-03-09", "runtime": 48}
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
        .formatted(
            5000 + seasonNumber,
            seasonNumber,
            seasonNumber,
            seasonNumber,
            episodesBuilder.toString());
  }
}
