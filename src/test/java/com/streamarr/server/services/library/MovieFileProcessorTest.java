package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fixtures.ArtworkServiceFixture;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.MetadataFixture;
import com.streamarr.server.services.ArtworkService;
import com.streamarr.server.services.CompanyService;
import com.streamarr.server.services.GenreService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.PersonService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.Found;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.NotFound;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.movie.TMDBMovieProvider;
import com.streamarr.server.services.metadata.tmdb.TmdbApiException;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.ExternalIdVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@ExtendWith(MockitoExtension.class)
@DisplayName("Movie File Processor Tests")
class MovieFileProcessorTest {

  private final MetadataProvider<Movie> tmdbMovieProvider = mock(TMDBMovieProvider.class);
  private final MovieMetadataProviderResolver movieMetadataProviderResolver =
      new MovieMetadataProviderResolver(List.of(tmdbMovieProvider));
  private final PersonService personService = mock(PersonService.class);
  private final GenreService genreService = mock(GenreService.class);
  private final CompanyService companyService = mock(CompanyService.class);
  private final FakeMediaFileRepository fakeMediaFileRepository = new FakeMediaFileRepository();
  private final FakeMovieRepository fakeMovieRepository = new FakeMovieRepository();
  private final ArtworkService artworkService =
      ArtworkServiceFixture.artworkServiceBuilder().build();
  private final MovieService movieService =
      new MovieService(
          fakeMovieRepository,
          personService,
          genreService,
          companyService,
          null,
          artworkService,
          null,
          null,
          null,
          null,
          null,
          null,
          null);

  private final MovieFileProcessor movieFileProcessor =
      new MovieFileProcessor(
          new DefaultVideoFileMetadataParser(),
          new ExternalIdVideoFileMetadataParser(),
          movieMetadataProviderResolver,
          movieService,
          fakeMediaFileRepository,
          new MutexFactoryProvider());

  @Test
  @DisplayName("Should restore interrupt flag when enrichment throws InterruptedException")
  void shouldRestoreInterruptFlagWhenEnrichmentThrowsInterruptedException() {
    var library = LibraryFixtureCreator.buildFakeLibrary();

    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///library/About%20Time/About%20Time%20(2013).mkv")
                .filename("About Time (2013).mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);

    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            new Found(
                RemoteSearchResult.builder()
                    .title("About Time")
                    .externalId("123")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenAnswer(
            invocation -> {
              throw new InterruptedException("simulated interrupt during metadata fetch");
            });

    try {
      movieFileProcessor.process(discoveryOf(library), mediaFile);

      assertThat(Thread.currentThread().isInterrupted())
          .as("Interrupt flag should be restored after InterruptedException is caught")
          .isTrue();
    } finally {
      // Clear the interrupt flag so it doesn't affect other tests
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should mark metadata not found when provider finds no match")
  void shouldMarkMetadataNotFoundWhenProviderFindsNoMatch() {
    var library = LibraryFixtureCreator.buildFakeLibrary();

    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///library/Obscure%20Film%20(1999)/Obscure.Film.1999.mkv")
                .filename("Obscure.Film.1999.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class))).thenReturn(new NotFound());

    movieFileProcessor.process(discoveryOf(library), mediaFile);

    assertThat(fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.METADATA_NOT_FOUND);
  }

  @Test
  @DisplayName("Should mark metadata unavailable when provider is temporarily unavailable")
  void shouldMarkMetadataUnavailableWhenProviderIsTemporarilyUnavailable() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///library/Cop%20Land%20(1997)/Cop.Land.1997.mkv")
                .filename("Cop.Land.1997.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());
    var timeout = new IOException("Connection timed out");

    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(new TemporarilyUnavailable(timeout));

    movieFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(
        mediaFile, MediaFileStatus.METADATA_UNAVAILABLE, ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should mark a misconfiguration when the provider rejects the credentials")
  void shouldMarkAMisconfigurationWhenTheProviderRejectsTheCredentials() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var mediaFile = saveMovieFile(library);
    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(new TemporarilyUnavailable(new TmdbApiException(401, "Invalid API key")));

    movieFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(
        mediaFile, MediaFileStatus.METADATA_UNAVAILABLE, ItemFailureReason.MISCONFIGURED);
  }

  @Test
  @DisplayName("Should mark an enrichment failure with its reason when the metadata fetch fails")
  void shouldMarkAnEnrichmentFailureWithItsReasonWhenTheMetadataFetchFails() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var mediaFile = saveMovieFile(library);
    stubSearchFound();
    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("Connection reset")));

    movieFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(
        mediaFile, MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should mark metadata not found when the provider no longer has the movie")
  void shouldMarkMetadataNotFoundWhenTheProviderNoLongerHasTheMovie() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var mediaFile = saveMovieFile(library);
    stubSearchFound();
    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(new MetadataFetchOutcome.NotFound<>());

    movieFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(mediaFile, MediaFileStatus.METADATA_NOT_FOUND, null);
  }

  @Test
  @DisplayName("Should mark a temporary enrichment failure when saving the movie throws")
  void shouldMarkATemporaryEnrichmentFailureWhenSavingTheMovieThrows() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var mediaFile = saveMovieFile(library);
    stubSearchFound();
    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                MetadataFixture.<Movie>metadataResultBuilder()
                    .entity(Movie.builder().title("About Time").build())
                    .build()));
    when(personService.getOrCreatePersons(any(), any()))
        .thenThrow(new IllegalStateException("db hiccup"));

    movieFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(
        mediaFile, MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should clear the earlier failure reason when the movie is matched")
  void shouldClearTheEarlierFailureReasonWhenTheMovieIsMatched() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var mediaFile = saveMovieFile(library);
    mediaFile.setStatus(MediaFileStatus.ENRICHMENT_FAILED);
    mediaFile.setFailureReason(ItemFailureReason.TEMPORARY);
    stubSearchFound();
    fakeMovieRepository.save(movieWithTmdbId("123"));

    movieFileProcessor.process(discoveryOf(library), mediaFile);

    assertMatchingFailure(mediaFile, MediaFileStatus.MATCHED, null);
  }

  @Test
  @DisplayName("Should keep a newer match when a stale attempt fails afterwards")
  void shouldKeepANewerMatchWhenAStaleAttemptFailsAfterwards() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var staleCopy = saveMovieFile(library);
    fakeMediaFileRepository.database.put(
        staleCopy.getId(),
        MediaFile.builder()
            .id(staleCopy.getId())
            .libraryId(library.getId())
            .filepathUri(staleCopy.getFilepathUri())
            .filename(staleCopy.getFilename())
            .status(MediaFileStatus.MATCHED)
            .build());
    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class))).thenReturn(new NotFound());

    movieFileProcessor.process(discoveryOf(library), staleCopy);

    assertMatchingFailure(staleCopy, MediaFileStatus.MATCHED, null);
  }

  @Test
  @DisplayName("Should report the database error when a matching failure cannot be recorded")
  void shouldReportTheDatabaseErrorWhenAMatchingFailureCannotBeRecorded() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var mediaFile = saveMovieFile(library);
    stubSearchFound();
    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("Connection reset")));
    var failure = new DataAccessResourceFailureException("database unavailable");
    fakeMediaFileRepository.failMatchingFailureWritesWith(failure);

    var discovery = discoveryOf(library);

    assertThatThrownBy(() -> movieFileProcessor.process(discovery, mediaFile)).isSameAs(failure);
  }

  private MediaFile saveMovieFile(Library library) {
    return fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .filepathUri("file:///library/About%20Time/About%20Time%20(2013).mkv")
            .filename("About Time (2013).mkv")
            .status(MediaFileStatus.UNMATCHED)
            .build());
  }

  private void stubSearchFound() {
    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            new Found(
                RemoteSearchResult.builder()
                    .title("About Time")
                    .externalId("123")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));
  }

  private static Movie movieWithTmdbId(String tmdbId) {
    return Movie.builder()
        .title("About Time")
        .externalIds(
            Set.of(
                ExternalIdentifier.builder()
                    .externalSourceType(ExternalSourceType.TMDB)
                    .externalId(tmdbId)
                    .build()))
        .build();
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
