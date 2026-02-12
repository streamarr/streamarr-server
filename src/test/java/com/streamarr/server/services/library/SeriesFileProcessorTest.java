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
import java.util.List;
import java.util.Optional;
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
  void shouldRestoreInterruptFlagWhenEnrichmentThrowsInterruptedException() throws Exception {
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
}
