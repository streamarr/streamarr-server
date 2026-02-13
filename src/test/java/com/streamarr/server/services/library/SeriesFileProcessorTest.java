package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.series.SeriesMetadataProvider;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.parsers.show.EpisodePathMetadataParser;
import com.streamarr.server.services.parsers.show.SeasonPathMetadataParser;
import com.streamarr.server.services.parsers.show.SeriesFolderNameParser;
import com.streamarr.server.services.parsers.show.regex.EpisodeRegexFixtures;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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
  private final FakeMediaFileRepository fakeMediaFileRepository = new FakeMediaFileRepository();
  private final SeasonRepository seasonRepository = mock(SeasonRepository.class);
  private final EpisodeRepository episodeRepository = mock(EpisodeRepository.class);
  private final CapturingEventPublisher capturingEventPublisher = new CapturingEventPublisher();

  private final SeriesFileProcessor seriesFileProcessor =
      new SeriesFileProcessor(
          new EpisodePathMetadataParser(new EpisodeRegexFixtures()),
          new SeasonPathMetadataParser(),
          new SeriesFolderNameParser(),
          seriesMetadataProviderResolver,
          new DateBasedEpisodeResolver(seriesMetadataProviderResolver),
          seriesService,
          fakeMediaFileRepository,
          seasonRepository,
          episodeRepository,
          new MutexFactoryProvider(),
          capturingEventPublisher);

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
            Optional.of(
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
      seriesFileProcessor.process(library, mediaFile);

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
                .filepathUri(
                    "file:///library/Breaking%20Bad/Season%2001/Breaking.Bad.S01E01.mkv")
                .filename("Breaking.Bad.S01E01.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    when(seriesMetadataProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);

    when(seriesMetadataProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            Optional.of(
                RemoteSearchResult.builder()
                    .title("Breaking Bad")
                    .externalId("1396")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(seriesService.findByTmdbId(anyString())).thenReturn(Optional.empty());

    when(seriesMetadataProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(Optional.empty());

    seriesFileProcessor.process(library, mediaFile);

    assertThat(fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.ENRICHMENT_FAILED);
  }

  @Test
  @DisplayName("Should mark ENRICHMENT_FAILED when year-based season resolution fails")
  void shouldMarkEnrichmentFailedWhenYearBasedSeasonResolutionFails() {
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
            Optional.of(
                RemoteSearchResult.builder()
                    .title("The Daily Show")
                    .externalId("2224")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(seriesService.findByTmdbId("2224")).thenReturn(Optional.of(series));

    when(seasonRepository.findBySeriesIdAndSeasonNumber(series.getId(), 2020))
        .thenReturn(Optional.empty());

    when(seriesMetadataProvider.resolveSeasonNumber("2224", 2020))
        .thenReturn(OptionalInt.empty());

    seriesFileProcessor.process(library, mediaFile);

    assertThat(fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.ENRICHMENT_FAILED);
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
            Optional.of(
                RemoteSearchResult.builder()
                    .title("Justice League")
                    .externalId("93544")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(seriesService.findByTmdbId("93544")).thenReturn(Optional.of(series));

    when(seasonRepository.findBySeriesIdAndSeasonNumber(series.getId(), 4))
        .thenReturn(Optional.empty());

    when(seriesMetadataProvider.getSeasonDetails("93544", 4)).thenReturn(Optional.empty());

    seriesFileProcessor.process(library, mediaFile);

    assertThat(fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.ENRICHMENT_FAILED);
  }
}
