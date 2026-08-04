package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.RecordingMetadataProvider;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.CompanyService;
import com.streamarr.server.services.GenreService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.PersonService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.movie.TMDBMovieProvider;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.ExternalIdVideoFileMetadataParser;
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
  private final MovieService movieService =
      new MovieService(
          fakeMovieRepository,
          personService,
          genreService,
          companyService,
          null,
          null,
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
            Optional.of(
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
      movieFileProcessor.process(library, mediaFile);

      assertThat(Thread.currentThread().isInterrupted())
          .as("Interrupt flag should be restored after InterruptedException is caught")
          .isTrue();
    } finally {
      // Clear the interrupt flag so it doesn't affect other tests
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName(
      "Should use folder title for TMDB search when filename lacks year but folder has year")
  void shouldUseFolderTitleForTmdbSearchWhenFilenameLacksYearButFolderHasYear() {
    var library = LibraryFixtureCreator.buildFakeLibrary();

    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///library/Inception%20(2010)/movie.mkv")
                .filename("movie.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);

    when(tmdbMovieProvider.search(any(VideoFileParserResult.class))).thenReturn(Optional.empty());

    when(tmdbMovieProvider.search(
            argThat(r -> "Inception".equals(r.title()) && "2010".equals(r.year()))))
        .thenReturn(
            Optional.of(
                RemoteSearchResult.builder()
                    .title("Inception")
                    .externalId("27205")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(Optional.empty());

    movieFileProcessor.process(library, mediaFile);

    assertThat(fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.UNMATCHED);
  }

  @Test
  @DisplayName("Should search with the accented folder title when filename lacks the year")
  void shouldSearchWithAccentedFolderTitleWhenFilenameLacksTheYear() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var expectedSearch = VideoFileParserResult.builder().title("Amélie").year("2001").build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Amélie")
            .externalId("194")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();
    var metadataProvider = new RecordingMetadataProvider<Movie>();
    metadataProvider.willReturnSearchResultFor(expectedSearch, searchResult);
    var processor = movieFileProcessorWith(metadataProvider);

    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///library/Am%C3%A9lie%20(2001)/movie.mkv")
                .filename("movie.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    processor.process(library, mediaFile);

    assertThat(metadataProvider.searchRequests()).containsExactly(expectedSearch);
    assertThat(fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.UNMATCHED);
  }

  @Test
  @DisplayName("Should search from filepath URI when existing filename is mangled")
  void shouldSearchFromFilepathUriWhenExistingFilenameIsMangled() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var expectedSearch = VideoFileParserResult.builder().title("Déjà Vu").year("2006").build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Déjà Vu")
            .externalId("7551")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();
    var metadataProvider = new RecordingMetadataProvider<Movie>();
    metadataProvider.willReturnSearchResultFor(expectedSearch, searchResult);
    var processor = movieFileProcessorWith(metadataProvider);
    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri(
                    "file:///library/D%C3%A9j%C3%A0%20Vu%20(2006)/D%C3%A9j%C3%A0%20Vu%20(2006).mkv")
                .filename("D��j�� Vu (2006).mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    processor.process(library, mediaFile);

    assertThat(metadataProvider.searchRequests()).containsExactly(expectedSearch);
    assertThat(fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.UNMATCHED);
  }

  @Test
  @DisplayName("Should use filepath URI when stored filename is blank")
  void shouldUseFilepathUriWhenStoredFilenameIsBlank() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var expectedSearch =
        VideoFileParserResult.builder().title("Unknown.Movie").year("2024").build();
    var metadataProvider = new RecordingMetadataProvider<Movie>();
    var processor = movieFileProcessorWith(metadataProvider);

    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///library/Unknown%20Movie%20(2024)/Unknown.Movie.2024.mkv")
                .filename("")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    processor.process(library, mediaFile);

    assertThat(metadataProvider.searchRequests()).containsExactly(expectedSearch);
    assertThat(fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.METADATA_SEARCH_FAILED);
  }

  @Test
  @DisplayName("Should search with external ID from filepath URI when stored filename is mangled")
  void shouldSearchWithExternalIdFromFilepathUriWhenStoredFilenameIsMangled() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var expectedSearch =
        VideoFileParserResult.builder()
            .title("Inception")
            .year("2010")
            .externalId("tt1375666")
            .externalSource(ExternalSourceType.IMDB)
            .build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Inception")
            .externalId("27205")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();
    var metadataProvider = new RecordingMetadataProvider<Movie>();
    metadataProvider.willReturnSearchResultFor(expectedSearch, searchResult);
    var processor = movieFileProcessorWith(metadataProvider);
    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri(
                    "file:///library/Inception%20(2010)/"
                        + "Inception%20(2010)%20%5Bimdb-tt1375666%5D.mkv")
                .filename("legacy-mangled-name.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    processor.process(library, mediaFile);

    assertThat(metadataProvider.searchRequests()).containsExactly(expectedSearch);
  }

  @Test
  @DisplayName("Should mark metadata search failed when provider finds no match")
  void shouldMarkMetadataSearchFailedWhenProviderFindsNoMatch() {
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
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class))).thenReturn(Optional.empty());

    movieFileProcessor.process(library, mediaFile);

    assertThat(fakeMediaFileRepository.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.METADATA_SEARCH_FAILED);
  }

  private MovieFileProcessor movieFileProcessorWith(MetadataProvider<Movie> metadataProvider) {
    return new MovieFileProcessor(
        new DefaultVideoFileMetadataParser(),
        new ExternalIdVideoFileMetadataParser(),
        new MovieMetadataProviderResolver(List.of(metadataProvider)),
        movieService,
        fakeMediaFileRepository,
        new MutexFactoryProvider());
  }
}
