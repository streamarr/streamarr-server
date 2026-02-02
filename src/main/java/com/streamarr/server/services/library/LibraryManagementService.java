package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryScanInProgressException;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.PersonService;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import java.io.IOException;
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

  private final VideoExtensionValidator videoExtensionValidator;
  private final DefaultVideoFileMetadataParser defaultVideoFileMetadataParser;
  private final MovieMetadataProviderResolver movieMetadataProviderResolver;
  private final LibraryRepository libraryRepository;
  private final MediaFileRepository mediaFileRepository;
  private final MovieService movieService;
  private final PersonService personService;
  private final FileSystem fileSystem;
  private final MutexFactory<String> mutexFactory;

  public LibraryManagementService(
      VideoExtensionValidator videoExtensionValidator,
      DefaultVideoFileMetadataParser defaultVideoFileMetadataParser,
      MovieMetadataProviderResolver movieMetadataProviderResolver,
      LibraryRepository libraryRepository,
      MediaFileRepository mediaFileRepository,
      MovieService movieService,
      PersonService personService,
      MutexFactoryProvider mutexFactoryProvider,
      FileSystem fileSystem) {
    this.videoExtensionValidator = videoExtensionValidator;
    this.defaultVideoFileMetadataParser = defaultVideoFileMetadataParser;
    this.movieMetadataProviderResolver = movieMetadataProviderResolver;
    this.libraryRepository = libraryRepository;
    this.mediaFileRepository = mediaFileRepository;
    this.movieService = movieService;
    this.personService = personService;
    this.fileSystem = fileSystem;

    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  public void addLibrary(Library library) {
    // validate library doesn't already exist.
    // save new library entity to database.
    // call refreshLibrary() once new library has been created.
    // return newly created library.
  }

  public void removeLibrary() {
    // remove watcher
    // cleanup?
    // terminate streams?
    // remove all db entries but leave files (shows, movies, etc)
    // delete "Library" entity
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
          .forEach(file -> executor.submit(() -> processFile(library, file)));

    } catch (IOException e) {
      var endTimeOfFailure = Instant.now();

      library.setStatus(LibraryStatus.UNHEALTHY);
      library.setScanCompletedOn(endTimeOfFailure);
      libraryRepository.save(library);

      log.error("Failed to access {} library during scan attempt.", library.getName(), e);

      return;
    }

    var endTime = Instant.now();
    var elapsedTime = Duration.between(startTime, endTime).getSeconds();

    library.setStatus(LibraryStatus.HEALTHY);
    library.setScanCompletedOn(endTime);
    libraryRepository.save(library);

    log.info("Finished {} library scan in {} seconds.", library.getName(), elapsedTime);
  }

  private void processFile(Library library, Path path) {

    if (isUnsupportedFileExtension(path)) {
      return;
    }

    var mediaFile = probeFile(library, path);

    if (isAlreadyMatched(mediaFile)) {
      return;
    }

    switch (library.getType()) {
      case MOVIE -> processMovieFileType(library, mediaFile);
      case SERIES -> throw new UnsupportedOperationException("Not implemented yet");
    }
  }

  private boolean isUnsupportedFileExtension(Path path) {
    var extension = getExtension(path);

    var valid = videoExtensionValidator.validate(extension);

    if (!valid) {
      log.error(
          "Unsupported file extension: {} for filepath {}.", extension, path.toAbsolutePath());
    }

    return !valid;
  }

  private String getExtension(Path path) {
    return FilenameUtils.getExtension(path.getFileName().toString());
  }

  private MediaFile probeFile(Library library, Path path) {
    var absoluteFilepath = path.toAbsolutePath().toString();

    var optionalMediaFile = mediaFileRepository.findFirstByFilepath(absoluteFilepath);

    if (optionalMediaFile.isPresent()) {
      log.info(
          "MediaFile id: '{}' already exists, not adding again.", optionalMediaFile.get().getId());
      return optionalMediaFile.get();
    }

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

    // Lock should use the library agent's external id.
    var mutex = mutexFactory.getMutex(remoteSearchResult.externalId());

    try {
      mutex.lock();

      updateOrSaveEnrichedMovie(library, mediaFile, remoteSearchResult);
    } catch (Exception ex) {
      log.error("Failure enriching movie metadata:", ex);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
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
    var savedCast = personService.getOrCreateCast(cast);

    movieToSave.get().setCast(savedCast);
    movieService.saveMovieWithMediaFile(movieToSave.get(), mediaFile);
    markMediaFileAsMatched(mediaFile);
  }

  private void markMediaFileAsMatched(MediaFile mediaFile) {
    mediaFile.setStatus(MediaFileStatus.MATCHED);
    mediaFileRepository.save(mediaFile);
  }

  private void deleteMissingMediaFiles() {
    // get all items in library
    // locate files in FS
    // cleanup if file cannot be located.
  }
}
