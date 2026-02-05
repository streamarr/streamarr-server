package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.exceptions.InvalidLibraryPathException;
import com.streamarr.server.exceptions.LibraryPathPermissionDeniedException;
import com.streamarr.server.exceptions.LibraryAlreadyExistsException;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryScanInProgressException;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.GenreService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.PersonService;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LibraryManagementService {

  private final IgnoredFileValidator ignoredFileValidator;
  private final VideoExtensionValidator videoExtensionValidator;
  private final DefaultVideoFileMetadataParser defaultVideoFileMetadataParser;
  private final MovieMetadataProviderResolver movieMetadataProviderResolver;
  private final LibraryRepository libraryRepository;
  private final MediaFileRepository mediaFileRepository;
  private final MovieService movieService;
  private final PersonService personService;
  private final GenreService genreService;
  private final OrphanedMediaFileCleanupService orphanedMediaFileCleanupService;
  private final FileSystem fileSystem;
  private final MutexFactory<String> mutexFactory;

  public LibraryManagementService(
      IgnoredFileValidator ignoredFileValidator,
      VideoExtensionValidator videoExtensionValidator,
      DefaultVideoFileMetadataParser defaultVideoFileMetadataParser,
      MovieMetadataProviderResolver movieMetadataProviderResolver,
      LibraryRepository libraryRepository,
      MediaFileRepository mediaFileRepository,
      MovieService movieService,
      PersonService personService,
      GenreService genreService,
      OrphanedMediaFileCleanupService orphanedMediaFileCleanupService,
      MutexFactoryProvider mutexFactoryProvider,
      FileSystem fileSystem) {
    this.ignoredFileValidator = ignoredFileValidator;
    this.videoExtensionValidator = videoExtensionValidator;
    this.defaultVideoFileMetadataParser = defaultVideoFileMetadataParser;
    this.movieMetadataProviderResolver = movieMetadataProviderResolver;
    this.libraryRepository = libraryRepository;
    this.mediaFileRepository = mediaFileRepository;
    this.movieService = movieService;
    this.personService = personService;
    this.genreService = genreService;
    this.orphanedMediaFileCleanupService = orphanedMediaFileCleanupService;
    this.fileSystem = fileSystem;

    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  public Library addLibrary(Library library) {
    validateFilepath(library.getFilepath());
    validateFilepathNotAlreadyUsed(library.getFilepath());
    validatePathExistsAndIsDirectory(library.getFilepath());

    var libraryToSave = library.toBuilder().status(LibraryStatus.HEALTHY).build();
    var savedLibrary = libraryRepository.save(libraryToSave);

    triggerAsyncScan(savedLibrary.getId());

    return savedLibrary;
  }

  private void validateFilepath(String filepath) {
    if (filepath == null || filepath.isBlank()) {
      throw new InvalidLibraryPathException(filepath, "filepath cannot be empty");
    }
  }

  private void validateFilepathNotAlreadyUsed(String filepath) {
    if (libraryRepository.existsByFilepath(filepath)) {
      throw new LibraryAlreadyExistsException(filepath);
    }
  }

  private void validatePathExistsAndIsDirectory(String filepath) {
    var path = fileSystem.getPath(filepath);
    try {
      if (!Files.exists(path)) {
        throw new InvalidLibraryPathException(filepath, "path does not exist");
      }
      if (!Files.isDirectory(path)) {
        throw new InvalidLibraryPathException(filepath, "path is not a directory");
      }
    } catch (SecurityException e) {
      throw new LibraryPathPermissionDeniedException(filepath);
    }
  }

  private void triggerAsyncScan(UUID libraryId) {
    Thread.startVirtualThread(
        () -> {
          try {
            scanLibrary(libraryId);
          } catch (Exception e) {
            log.error("Async library scan failed for library: {}", libraryId, e);
          }
        });
  }

  // TODO #39: implement removeLibrary mutation
  public void removeLibrary() {}

  public void processDiscoveredFile(UUID libraryId, Path path) {
    var library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(() -> new LibraryNotFoundException(libraryId));

    processFile(library, path);
  }

  public void scanLibrary(UUID libraryId) {
    var optionalLibrary = libraryRepository.findById(libraryId);

    if (optionalLibrary.isEmpty()) {
      throw new LibraryNotFoundException(libraryId);
    }

    var library = optionalLibrary.get();

    if (library.getStatus().equals(LibraryStatus.SCANNING)) {
      throw new LibraryScanInProgressException(libraryId);
    }

    log.info("Starting {} library scan.", library.getName());

    var startTime = Instant.now();

    library.setStatus(LibraryStatus.SCANNING);
    library.setScanStartedOn(startTime);
    libraryRepository.save(library);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var stream = Files.walk(fileSystem.getPath(library.getFilepath()))) {

      stream
          .filter(Files::isRegularFile)
          .filter(file -> !ignoredFileValidator.shouldIgnore(file))
          .forEach(file -> executor.submit(() -> processFile(library, file)));

    } catch (IOException | UncheckedIOException | SecurityException e) {
      var endTimeOfFailure = Instant.now();

      library.setStatus(LibraryStatus.UNHEALTHY);
      library.setScanCompletedOn(endTimeOfFailure);
      libraryRepository.save(library);

      log.error("Failed to access {} library during scan attempt.", library.getName(), e);

      return;
    }

    orphanedMediaFileCleanupService.cleanupOrphanedFiles(library);

    var endTime = Instant.now();
    var elapsedTime = Duration.between(startTime, endTime).getSeconds();

    library.setStatus(LibraryStatus.HEALTHY);
    library.setScanCompletedOn(endTime);
    libraryRepository.save(library);

    log.info("Finished {} library scan in {} seconds.", library.getName(), elapsedTime);
  }

  private void processFile(Library library, Path path) {

    if (!hasSupportedExtension(path)) {
      log.warn(
          "Unsupported file extension: {} for filepath {}.",
          getExtension(path),
          path.toAbsolutePath());
      return;
    }

    var mediaFile = probeFile(library, path);

    if (isAlreadyMatched(mediaFile)) {
      return;
    }

    switch (library.getType()) {
      case MOVIE -> processMovieFileType(library, mediaFile);
      case SERIES -> throw new UnsupportedOperationException("Series not yet supported. See #40");
      default -> throw new IllegalStateException("Unsupported media type: " + library.getType());
    }
  }

  private boolean hasSupportedExtension(Path path) {
    return videoExtensionValidator.validate(getExtension(path));
  }

  private String getExtension(Path path) {
    return FilenameUtils.getExtension(path.getFileName().toString());
  }

  private MediaFile probeFile(Library library, Path path) {
    var absoluteFilepath = path.toAbsolutePath().toString();
    var filepathMutex = mutexFactory.getMutex(absoluteFilepath);

    filepathMutex.lock();
    try {
      var optionalMediaFile = mediaFileRepository.findFirstByFilepath(absoluteFilepath);

      if (optionalMediaFile.isPresent()) {
        log.info(
            "MediaFile id: '{}' already exists, not adding again.",
            optionalMediaFile.get().getId());
        return optionalMediaFile.get();
      }

      return createNewMediaFile(library, path, absoluteFilepath);
    } finally {
      filepathMutex.unlock();
    }
  }

  private MediaFile createNewMediaFile(Library library, Path path, String absoluteFilepath) {
    long fileSize = 0;
    try {
      fileSize = Files.size(path);
    } catch (IOException | SecurityException ex) {
      log.error("Could not get filesize at path: {} media might be corrupt.", absoluteFilepath, ex);
    }

    return mediaFileRepository.save(
        MediaFile.builder()
            .status(MediaFileStatus.UNMATCHED)
            .filename(path.getFileName().toString())
            .filepath(absoluteFilepath)
            .size(fileSize)
            .libraryId(library.getId())
            .build());
  }

  private boolean isAlreadyMatched(MediaFile file) {
    return file.getStatus().equals(MediaFileStatus.MATCHED);
  }

  private void processMovieFileType(Library library, MediaFile mediaFile) {
    var mediaInformationResult = parseMediaFileForMovieInfo(mediaFile);

    if (mediaInformationResult.isEmpty()) {
      mediaFile.setStatus(MediaFileStatus.METADATA_PARSING_FAILED);
      mediaFileRepository.save(mediaFile);

      log.error(
          "Failed to parse MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepath());

      return;
    }

    log.info(
        "Parsed filename for MediaFile id: {}. Title: {} and Year: {}",
        mediaFile.getId(),
        mediaInformationResult.get().title(),
        mediaInformationResult.get().year());

    var movieSearchResult =
        movieMetadataProviderResolver.search(library, mediaInformationResult.get());

    if (movieSearchResult.isEmpty()) {
      mediaFile.setStatus(MediaFileStatus.METADATA_SEARCH_FAILED);
      mediaFileRepository.save(mediaFile);

      log.error(
          "Failed to find matching search result for MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepath());

      return;
    }

    log.info(
        "Found metadata search result during enrichment for MediaFile id: {}. Metadata provider: {} and External id: {}",
        mediaFile.getId(),
        movieSearchResult.get().externalSourceType(),
        movieSearchResult.get().externalId());

    enrichMovieMetadata(library, mediaFile, movieSearchResult.get());
  }

  private Optional<VideoFileParserResult> parseMediaFileForMovieInfo(MediaFile mediaFile) {
    var result = defaultVideoFileMetadataParser.parse(mediaFile.getFilename());

    if (result.isEmpty() || StringUtils.isEmpty(result.get().title())) {
      return Optional.empty();
    }

    return result;
  }

  private void enrichMovieMetadata(
      Library library, MediaFile mediaFile, RemoteSearchResult remoteSearchResult) {

    var externalIdMutex = mutexFactory.getMutex(remoteSearchResult.externalId());

    try {
      externalIdMutex.lock();

      updateOrSaveEnrichedMovie(library, mediaFile, remoteSearchResult);
    } catch (Exception ex) {
      log.error("Failure enriching movie metadata:", ex);
    } finally {
      if (externalIdMutex.isHeldByCurrentThread()) {
        externalIdMutex.unlock();
      }
    }
  }

  private void updateOrSaveEnrichedMovie(
      Library library, MediaFile mediaFile, RemoteSearchResult remoteSearchResult) {
    var optionalMovie =
        movieService.addMediaFileToMovieByTmdbId(remoteSearchResult.externalId(), mediaFile);

    if (optionalMovie.isPresent()) {
      markMediaFileAsMatched(mediaFile);
      return;
    }

    var movieToSave = movieMetadataProviderResolver.getMetadata(remoteSearchResult, library);

    if (movieToSave.isEmpty()) {
      return;
    }

    var cast = movieToSave.get().getCast();
    var savedCast = personService.getOrCreatePersons(cast);
    movieToSave.get().setCast(savedCast);

    var directors = movieToSave.get().getDirectors();
    var savedDirectors = personService.getOrCreatePersons(directors);
    movieToSave.get().setDirectors(savedDirectors);

    var genres = movieToSave.get().getGenres();
    var savedGenres = genreService.getOrCreateGenres(genres);
    movieToSave.get().setGenres(savedGenres);

    movieService.saveMovieWithMediaFile(movieToSave.get(), mediaFile);
    markMediaFileAsMatched(mediaFile);
  }

  private void markMediaFileAsMatched(MediaFile mediaFile) {
    mediaFile.setStatus(MediaFileStatus.MATCHED);
    mediaFileRepository.save(mediaFile);
  }
}
