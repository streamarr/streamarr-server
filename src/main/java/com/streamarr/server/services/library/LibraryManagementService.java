package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.exceptions.InvalidLibraryPathException;
import com.streamarr.server.exceptions.LibraryAlreadyExistsException;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryPathPermissionDeniedException;
import com.streamarr.server.exceptions.LibraryScanFailedException;
import com.streamarr.server.exceptions.LibraryScanInProgressException;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.library.events.LibraryAddedEvent;
import com.streamarr.server.services.library.events.LibraryRemovedEvent;
import com.streamarr.server.services.library.events.ScanCompletedEvent;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class LibraryManagementService implements ActiveScanChecker {

  private final IgnoredFileValidator ignoredFileValidator;
  private final VideoExtensionValidator videoExtensionValidator;
  private final MovieFileProcessor movieFileProcessor;
  private final SeriesFileProcessor seriesFileProcessor;
  private final LibraryRepository libraryRepository;
  private final MediaFileRepository mediaFileRepository;
  private final MovieService movieService;
  private final SeriesService seriesService;
  private final ApplicationEventPublisher eventPublisher;
  private final FileSystem fileSystem;
  private final MutexFactory<String> mutexFactory;
  private final Set<UUID> activeScans = ConcurrentHashMap.newKeySet();

  public LibraryManagementService(
      IgnoredFileValidator ignoredFileValidator,
      VideoExtensionValidator videoExtensionValidator,
      MovieFileProcessor movieFileProcessor,
      SeriesFileProcessor seriesFileProcessor,
      LibraryRepository libraryRepository,
      MediaFileRepository mediaFileRepository,
      MovieService movieService,
      SeriesService seriesService,
      ApplicationEventPublisher eventPublisher,
      MutexFactoryProvider mutexFactoryProvider,
      FileSystem fileSystem) {
    this.ignoredFileValidator = ignoredFileValidator;
    this.videoExtensionValidator = videoExtensionValidator;
    this.movieFileProcessor = movieFileProcessor;
    this.seriesFileProcessor = seriesFileProcessor;
    this.libraryRepository = libraryRepository;
    this.mediaFileRepository = mediaFileRepository;
    this.movieService = movieService;
    this.seriesService = seriesService;
    this.eventPublisher = eventPublisher;
    this.fileSystem = fileSystem;

    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  @Override
  public boolean isActivelyScanning(UUID libraryId) {
    return activeScans.contains(libraryId);
  }

  public Library addLibrary(Library library) {
    validateFilepath(library.getFilepath());
    validateFilepathNotAlreadyUsed(library.getFilepath());
    validatePathExistsAndIsDirectory(library.getFilepath());

    var libraryToSave = library.toBuilder().status(LibraryStatus.HEALTHY).build();
    var savedLibrary = libraryRepository.save(libraryToSave);

    eventPublisher.publishEvent(
        new LibraryAddedEvent(savedLibrary.getId(), savedLibrary.getFilepath()));

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

  @Transactional
  public void removeLibrary(UUID libraryId) {
    var libraryMutex = mutexFactory.getMutex(libraryId.toString());
    libraryMutex.lock();

    try {
      var library = findLibraryOrThrow(libraryId);
      rejectIfScanning(library);

      var mediaFiles = mediaFileRepository.findByLibraryId(libraryId);
      var mediaFileIds = extractMediaFileIds(mediaFiles);

      deleteLibraryContent(libraryId, mediaFiles);
      libraryRepository.delete(library);

      eventPublisher.publishEvent(new LibraryRemovedEvent(library.getFilepath(), mediaFileIds));
    } finally {
      libraryMutex.unlock();
    }
  }

  private Library findLibraryOrThrow(UUID libraryId) {
    return libraryRepository
        .findById(libraryId)
        .orElseThrow(() -> new LibraryNotFoundException(libraryId));
  }

  private void rejectIfScanning(Library library) {
    if (library.getStatus() == LibraryStatus.SCANNING) {
      throw new LibraryScanInProgressException(library.getId());
    }
  }

  private Set<UUID> extractMediaFileIds(List<MediaFile> mediaFiles) {
    return mediaFiles.stream().map(MediaFile::getId).collect(Collectors.toSet());
  }

  private void deleteLibraryContent(UUID libraryId, List<MediaFile> mediaFiles) {
    movieService.deleteByLibraryId(libraryId);
    seriesService.deleteByLibraryId(libraryId);
    mediaFileRepository.deleteAll(mediaFiles);
  }

  public void processDiscoveredFile(UUID libraryId, Path path) {
    var library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(() -> new LibraryNotFoundException(libraryId));

    processFile(library, path);
  }

  public void scanLibrary(UUID libraryId) {
    if (!activeScans.add(libraryId)) {
      throw new LibraryScanInProgressException(libraryId);
    }

    try {
      var library = transitionToScanning(libraryId);
      var startTime = library.getScanStartedOn();

      try {
        walkAndProcessFiles(library);
        completeScanSuccessfully(library, startTime);
      } catch (LibraryScanFailedException e) {
        completeScanWithFailure(library, e.getCause());
      }
    } finally {
      activeScans.remove(libraryId);
    }
  }

  private void walkAndProcessFiles(Library library) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var stream = Files.walk(fileSystem.getPath(library.getFilepath()))) {

      stream
          .filter(Files::isRegularFile)
          .filter(file -> !ignoredFileValidator.shouldIgnore(file))
          .forEach(file -> executor.submit(() -> processFile(library, file)));

    } catch (IOException | UncheckedIOException | SecurityException e) {
      throw new LibraryScanFailedException(library.getName(), e);
    }
  }

  private void completeScanSuccessfully(Library library, Instant startTime) {
    eventPublisher.publishEvent(new ScanCompletedEvent(library.getId()));

    var endTime = Instant.now();
    var elapsedSeconds = Duration.between(startTime, endTime).getSeconds();

    library.setStatus(LibraryStatus.HEALTHY);
    library.setScanCompletedOn(endTime);
    libraryRepository.save(library);

    log.info("Finished {} library scan in {} seconds.", library.getName(), elapsedSeconds);
  }

  private void completeScanWithFailure(Library library, Throwable cause) {
    library.setStatus(LibraryStatus.UNHEALTHY);
    library.setScanCompletedOn(Instant.now());
    libraryRepository.save(library);

    log.error("Failed to access {} library during scan attempt.", library.getName(), cause);
  }

  private Library transitionToScanning(UUID libraryId) {
    var libraryMutex = mutexFactory.getMutex(libraryId.toString());
    libraryMutex.lock();

    try {
      var library =
          libraryRepository
              .findById(libraryId)
              .orElseThrow(() -> new LibraryNotFoundException(libraryId));

      if (library.getStatus().equals(LibraryStatus.SCANNING)) {
        throw new LibraryScanInProgressException(libraryId);
      }

      log.info("Starting {} library scan.", library.getName());

      library.setStatus(LibraryStatus.SCANNING);
      library.setScanStartedOn(Instant.now());
      libraryRepository.save(library);

      return library;
    } finally {
      libraryMutex.unlock();
    }
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
      case MOVIE -> movieFileProcessor.process(library, mediaFile);
      case SERIES -> seriesFileProcessor.process(library, mediaFile);
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
}
