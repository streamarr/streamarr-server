package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fixtures.ArtworkServiceFixture;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.ArtworkService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.Found;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.NotFound;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.series.SeriesMetadataProvider;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.metadata.tmdb.TmdbApiException;
import com.streamarr.server.services.parsers.show.EpisodePathMetadataParser;
import com.streamarr.server.services.parsers.show.SeasonPathMetadataParser;
import com.streamarr.server.services.parsers.show.SeriesFolderNameParser;
import com.streamarr.server.services.parsers.show.regex.EpisodeRegexFixtures;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("UnitTest")
@ExtendWith(MockitoExtension.class)
@DisplayName("Series File Processor Tests")
class SeriesFileProcessorTest {

  private final SeriesMetadataProvider seriesMetadataProvider = mock(SeriesMetadataProvider.class);
  private final SeriesMetadataProviderResolver seriesMetadataProviderResolver =
      new SeriesMetadataProviderResolver(List.of(seriesMetadataProvider));
  private final SeriesService seriesService = mock(SeriesService.class);
  private final ArtworkService artworkService =
      ArtworkServiceFixture.artworkServiceBuilder().build();
  private final FakeMediaFileRepository fakeMediaFileRepository = new FakeMediaFileRepository();
  private final FakeSeasonRepository fakeSeasonRepository = new FakeSeasonRepository();
  private final FakeEpisodeRepository fakeEpisodeRepository = new FakeEpisodeRepository();
  private final SeriesFileProcessor seriesFileProcessor =
      new SeriesFileProcessor(
          new EpisodePathMetadataParser(new EpisodeRegexFixtures()),
          new SeasonPathMetadataParser(),
          new SeriesFolderNameParser(),
          seriesMetadataProviderResolver,
          new DateBasedEpisodeResolver(seriesMetadataProviderResolver),
          seriesService,
          fakeMediaFileRepository,
          fakeSeasonRepository,
          fakeEpisodeRepository,
          new MutexFactoryProvider());

  @Test
  @DisplayName("Should restore interrupt flag when enrichment throws InterruptedException")
  void shouldRestoreInterruptFlagWhenEnrichmentThrowsInterruptedException() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();

    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///library/Breaking%20Bad/Season%2001/Breaking.Bad.S01E01.mkv")
                .filename("Breaking.Bad.S01E01.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    when(seriesMetadataProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);

    when(seriesMetadataProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            new Found(
                RemoteSearchResult.builder()
                    .title("Breaking Bad")
                    .externalId("1396")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(seriesService.findByTmdbId(anyString())).thenReturn(Optional.empty());

    when(seriesMetadataProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenAnswer(
            invocation -> {
              throw new InterruptedException("simulated interrupt during metadata fetch");
            });

    try {
      seriesFileProcessor.process(discoveryOf(library), mediaFile);

      assertThat(Thread.currentThread().isInterrupted())
          .as("Interrupt flag should be restored after InterruptedException is caught")
          .isTrue();
    } finally {
      // Clear the interrupt flag so it doesn't affect other tests
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should mark ENRICHMENT_FAILED when series metadata fetch fails")
  void shouldMarkEnrichmentFailedWhenSeriesMetadataFetchFails() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();

    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///library/Breaking%20Bad/Season%2001/Breaking.Bad.S01E01.mkv")
                .filename("Breaking.Bad.S01E01.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    when(seriesMetadataProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);

    when(seriesMetadataProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            new Found(
                RemoteSearchResult.builder()
                    .title("Breaking Bad")
                    .externalId("1396")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(seriesService.findByTmdbId(anyString())).thenReturn(Optional.empty());

    when(seriesMetadataProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("simulated fetch failure")));

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(
        mediaFile, MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should mark metadata not found when the provider no longer has the series")
  void shouldMarkMetadataNotFoundWhenTheProviderNoLongerHasTheSeries() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();
    var mediaFile = saveEpisodeFile(library, "Breaking%20Bad/Season%2001/Breaking.Bad.S01E01.mkv");
    stubSearchFound("Breaking Bad", "1396");
    when(seriesService.findByTmdbId("1396")).thenReturn(Optional.empty());
    when(seriesMetadataProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(new MetadataFetchOutcome.NotFound<>());

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(mediaFile, MediaFileStatus.METADATA_NOT_FOUND, null);
  }

  @Test
  @DisplayName("Should mark a temporary enrichment failure when creating the series throws")
  void shouldMarkATemporaryEnrichmentFailureWhenCreatingTheSeriesThrows() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();
    var mediaFile = saveEpisodeFile(library, "Breaking%20Bad/Season%2001/Breaking.Bad.S01E01.mkv");
    stubSearchFound("Breaking Bad", "1396");
    when(seriesService.findByTmdbId("1396")).thenThrow(new IllegalStateException("db hiccup"));

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(
        mediaFile, MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should mark metadata not found when the provider has no such season")
  void shouldMarkMetadataNotFoundWhenTheProviderHasNoSuchSeason() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();
    var mediaFile = saveEpisodeFile(library, "Breaking%20Bad/Season%2009/Breaking.Bad.S09E01.mkv");
    stubSearchFound("Breaking Bad", "1396");
    when(seriesService.findByTmdbId("1396"))
        .thenReturn(Optional.of(Series.builder().id(UUID.randomUUID()).build()));
    when(seriesMetadataProvider.getSeasonDetails(isNull(), eq("1396"), eq(9)))
        .thenReturn(new MetadataFetchOutcome.NotFound<>());

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(mediaFile, MediaFileStatus.METADATA_NOT_FOUND, null);
  }

  @Test
  @DisplayName("Should mark metadata not found when year-based season resolution fails")
  void shouldMarkMetadataNotFoundWhenYearBasedSeasonResolutionFails() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();

    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri(
                    "file:///library/The%20Daily%20Show/Season%202020/The.Daily.Show.S01E01.mkv")
                .filename("The.Daily.Show.S01E01.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    var series = Series.builder().id(UUID.randomUUID()).title("The Daily Show").build();

    when(seriesMetadataProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);

    when(seriesMetadataProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            new Found(
                RemoteSearchResult.builder()
                    .title("The Daily Show")
                    .externalId("2224")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(seriesService.findByTmdbId("2224")).thenReturn(Optional.of(series));

    when(seriesMetadataProvider.resolveSeasonNumber(isNull(), eq("2224"), eq(2020)))
        .thenReturn(new MetadataFetchOutcome.NotFound<>());

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(mediaFile, MediaFileStatus.METADATA_NOT_FOUND, null);
  }

  @Test
  @DisplayName("Should mark ENRICHMENT_FAILED when season details fetch fails")
  void shouldMarkEnrichmentFailedWhenSeasonDetailsFetchFails() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();

    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri(
                    "file:///library/Justice%20League/Season%2004/Justice.League.S04E01.mkv")
                .filename("Justice.League.S04E01.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    var series = Series.builder().id(UUID.randomUUID()).title("Justice League").build();

    when(seriesMetadataProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);

    when(seriesMetadataProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            new Found(
                RemoteSearchResult.builder()
                    .title("Justice League")
                    .externalId("93544")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(seriesService.findByTmdbId("93544")).thenReturn(Optional.of(series));

    when(seriesMetadataProvider.getSeasonDetails(isNull(), eq("93544"), eq(4)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("simulated fetch failure")));

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(
        mediaFile, MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should mark metadata parsing failed when path has no episode info")
  void shouldMarkMetadataParsingFailedWhenPathHasNoEpisodeInfo() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();

    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///library/Nature%20Documentary/beach.mkv")
                .filename("beach.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertThat(fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.METADATA_PARSING_FAILED);
  }

  @Test
  @DisplayName("Should mark metadata not found when provider finds no match")
  void shouldMarkMetadataNotFoundWhenProviderFindsNoMatch() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();

    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///library/Unknown%20Show/Season%2001/Unknown.Show.S01E01.mkv")
                .filename("Unknown.Show.S01E01.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    when(seriesMetadataProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(seriesMetadataProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(new NotFound());

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertThat(fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.METADATA_NOT_FOUND);
  }

  @Test
  @DisplayName("Should mark metadata unavailable when provider is temporarily unavailable")
  void shouldMarkMetadataUnavailableWhenProviderIsTemporarilyUnavailable() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();
    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///library/Slow%20Show/Season%2001/Slow.Show.S01E01.mkv")
                .filename("Slow.Show.S01E01.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    when(seriesMetadataProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(seriesMetadataProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(new TemporarilyUnavailable(new IOException("Connection timed out")));

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(
        mediaFile, MediaFileStatus.METADATA_UNAVAILABLE, ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should mark an enrichment failure when a date-named episode's season details fail")
  void shouldMarkAnEnrichmentFailureWhenADateNamedEpisodesSeasonDetailsFail() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();
    var mediaFile = saveEpisodeFile(library, "Daily%20News/Daily%20News%20-%202025-11-25.mkv");
    stubSearchFound("Daily News", "5555");
    when(seriesMetadataProvider.resolveSeasonNumber(isNull(), eq("5555"), eq(2025)))
        .thenReturn(new MetadataFetchOutcome.Found<>(10));
    when(seriesMetadataProvider.getSeasonDetails(isNull(), eq("5555"), eq(10)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("Connection reset")));

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(
        mediaFile, MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should mark a misconfiguration when a date-named episode's season list is rejected")
  void shouldMarkAMisconfigurationWhenADateNamedEpisodesSeasonListIsRejected() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();
    var mediaFile = saveEpisodeFile(library, "Daily%20News/Daily%20News%20-%202025-11-25.mkv");
    stubSearchFound("Daily News", "5555");
    when(seriesMetadataProvider.resolveSeasonNumber(isNull(), eq("5555"), eq(2025)))
        .thenReturn(
            new MetadataFetchOutcome.Failed<>(new TmdbApiException(401, "Invalid API key")));

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(
        mediaFile, MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.MISCONFIGURED);
  }

  @Test
  @DisplayName("Should mark a temporary enrichment failure when a year season's list cannot load")
  void shouldMarkATemporaryEnrichmentFailureWhenAYearSeasonsListCannotLoad() {
    var library = LibraryFixtureCreator.buildFakeSeriesLibrary();
    var mediaFile =
        saveEpisodeFile(library, "The%20Daily%20Show/Season%202020/The.Daily.Show.S01E01.mkv");
    stubSearchFound("The Daily Show", "2224");
    when(seriesService.findByTmdbId("2224"))
        .thenReturn(Optional.of(Series.builder().id(UUID.randomUUID()).build()));
    when(seriesMetadataProvider.resolveSeasonNumber(isNull(), eq("2224"), eq(2020)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("Connection reset")));

    seriesFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(
        mediaFile, MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY);
  }

  private MediaFile saveEpisodeFile(Library library, String relativeUri) {
    return fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .filepathUri("file:///library/" + relativeUri)
            .filename(relativeUri.substring(relativeUri.lastIndexOf('/') + 1))
            .status(MediaFileStatus.UNMATCHED)
            .build());
  }

  private void stubSearchFound(String title, String externalId) {
    when(seriesMetadataProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(seriesMetadataProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            new Found(
                RemoteSearchResult.builder()
                    .title(title)
                    .externalId(externalId)
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));
  }

  private void assertMatchingFailure(
      MediaFile mediaFile, MediaFileStatus status, ItemFailureReason reason) {
    var stored = fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(status);
    assertThat(stored.getFailureReason()).isEqualTo(reason);
  }

  private FileDiscovery discoveryOf(Library library) {
    return new FileDiscovery(library, artworkService.openRun("scan", ImageRefreshMode.PRESERVE));
  }
}
