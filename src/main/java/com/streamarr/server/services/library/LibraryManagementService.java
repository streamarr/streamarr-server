package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryRefreshInProgressException;
import com.streamarr.server.exceptions.LibraryScanFailedException;
import com.streamarr.server.exceptions.LibraryScanInProgressException;
import com.streamarr.server.repositories.LibraryMetadataRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.events.library.ItemProcessedEvent;
import com.streamarr.server.services.events.library.LibraryAddedEvent;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import com.streamarr.server.services.events.library.RefreshEndedEvent;
import com.streamarr.server.services.events.library.ScanCompletedEvent;
import com.streamarr.server.services.events.library.ScanEndedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LibraryManagementService implements ActiveScanChecker, LibraryScanTrigger {

  /**
   * The unique index from V035 that makes a duplicate path a database decision, not a pre-check.
   */
  static final String LIBRARY_FILEPATH_UNIQUE = "library_filepath_uri_idx";

  private final IgnoredFileValidator ignoredFileValidator;
  private final VideoExtensionValidator videoExtensionValidator;
  private final MovieFileProcessor movieFileProcessor;
  private final SeriesFileProcessor seriesFileProcessor;
  private final LibraryRepository libraryRepository;
  private final LibraryMetadataRepository libraryMetadataRepository;
  private final MediaFileRepository mediaFileRepository;
  private final MovieService movieService;
  private final SeriesService seriesService;
  private final ApplicationEventPublisher eventPublisher;
  private final LibraryRefreshService libraryRefreshService;
  private final FileSystem fileSystem;
  private final LibraryMutationTransaction libraryMutationTransaction;
  private final MutexFactory<String> mutexFactory;
  private final MutationTransactions mutationTransactions;
  private final Set<UUID> activeScans = ConcurrentHashMap.newKeySet();
  private final Set<UUID> activeRefreshes = ConcurrentHashMap.newKeySet();

  public LibraryManagementService(
      IgnoredFileValidator ignoredFileValidator,
      VideoExtensionValidator videoExtensionValidator,
      MovieFileProcessor movieFileProcessor,
      SeriesFileProcessor seriesFileProcessor,
      LibraryRepository libraryRepository,
      LibraryMetadataRepository libraryMetadataRepository,
      MediaFileRepository mediaFileRepository,
      MovieService movieService,
      SeriesService seriesService,
      ApplicationEventPublisher eventPublisher,
      MutexFactoryProvider mutexFactoryProvider,
      LibraryRefreshService libraryRefreshService,
      FileSystem fileSystem,
      LibraryMutationTransaction libraryMutationTransaction,
      MutationTransactions mutationTransactions) {
    this.ignoredFileValidator = ignoredFileValidator;
    this.videoExtensionValidator = videoExtensionValidator;
    this.movieFileProcessor = movieFileProcessor;
    this.seriesFileProcessor = seriesFileProcessor;
    this.libraryRepository = libraryRepository;
    this.libraryMetadataRepository = libraryMetadataRepository;
    this.mediaFileRepository = mediaFileRepository;
    this.movieService = movieService;
    this.seriesService = seriesService;
    this.eventPublisher = eventPublisher;
    this.libraryRefreshService = libraryRefreshService;
    this.fileSystem = fileSystem;
    this.libraryMutationTransaction = libraryMutationTransaction;
    this.mutationTransactions = mutationTransactions;

    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  @Override
  public boolean isActivelyScanning(UUID libraryId) {
    return activeScans.contains(libraryId);
  }

  public List<LibraryMetadata> getAlphabetIndex(UUID libraryId) {
    return libraryMetadataRepository.findByLibraryIdOrderByLetterAsc(libraryId);
  }

  /**
   * Decides before it writes: name and path are validated first, then the insert runs in one
   * transaction that publishes {@link LibraryAddedEvent} for the AFTER_COMMIT listeners that start
   * watching and scanning. Two concurrent adds of the same path race on the unique index; the
   * loser's violation is translated after rollback into {@code PathAlreadyRegistered} and it
   * performs no side effect.
   */
  public Outcome<Library, AddLibraryRejection> addLibrary(
      AuthenticatedIdentity identity, Library library) {
    var rejections = new ArrayList<AddLibraryRejection>();
    if (library.getName() == null || library.getName().isBlank()) {
      rejections.add(new AddLibraryRejection.NameRequired());
    }

    var path = validatedPath(library.getFilepathUri(), rejections);
    if (!rejections.isEmpty()) {
      return Outcome.rejected(rejections);
    }

    var libraryToSave =
        library.toBuilder()
            .filepathUri(FilepathCodec.encode(path.orElseThrow()))
            .status(LibraryStatus.HEALTHY)
            .build();
    return mutationTransactions.write(
        () ->
            libraryMutationTransaction.execute(
                identity,
                new Intent.AddLibrary(),
                () -> {
                  var saved = libraryRepository.save(libraryToSave);
                  eventPublisher.publishEvent(
                      new LibraryAddedEvent(saved.getId(), saved.getFilepathUri()));
                  return saved.toBuilder().build();
                }),
        constraint ->
            LIBRARY_FILEPATH_UNIQUE.equals(constraint)
                ? Optional.of(new AddLibraryRejection.PathAlreadyRegistered())
                : Optional.empty());
  }

  private Optional<Path> validatedPath(String rawFilepath, List<AddLibraryRejection> rejections) {
    if (rawFilepath == null || rawFilepath.isBlank()) {
      rejections.add(new AddLibraryRejection.PathRequired());
      return Optional.empty();
    }

    try {
      var path = fileSystem.getPath(rawFilepath);
      pathRejection(path).ifPresent(rejections::add);
      return Optional.of(path);
    } catch (InvalidPathException _) {
      rejections.add(new AddLibraryRejection.PathNotFound());
    } catch (SecurityException _) {
      rejections.add(new AddLibraryRejection.PathNotReadable());
    }

    return Optional.empty();
  }

  private static Optional<AddLibraryRejection> pathRejection(Path path) {
    if (!Files.exists(path)) {
      return Optional.of(new AddLibraryRejection.PathNotFound());
    }

    if (!Files.isDirectory(path)) {
      return Optional.of(new AddLibraryRejection.PathNotDirectory());
    }

    if (!Files.isReadable(path)) {
      return Optional.of(new AddLibraryRejection.PathNotReadable());
    }

    return Optional.empty();
  }

  @Override
  public void triggerAsyncScan(UUID libraryId) {
    Thread.startVirtualThread(
        () -> {
          try {
            scanLibrary(libraryId);
          } catch (Exception e) {
            log.error("Async library scan failed for library: {}", libraryId, e);
          }
        });
  }

  public void triggerAsyncRefresh(UUID libraryId) {
    startAsyncRefresh(libraryId, () -> refreshLibrary(libraryId));
  }

  public void triggerAsyncRefresh(UUID libraryId, ImageRefreshMode imageRefreshMode) {
    startAsyncRefresh(libraryId, () -> refreshLibrary(libraryId, imageRefreshMode));
  }

  private void startAsyncRefresh(UUID libraryId, Runnable refresh) {
    Thread.startVirtualThread(
        () -> {
          try {
            refresh.run();
          } catch (Exception e) {
            log.error("Async library refresh failed for library: {}", libraryId, e);
          }
        });
  }

  public void removeLibrary(AuthenticatedIdentity identity, UUID libraryId) {
    var libraryMutex = mutexFactory.getMutex(libraryId.toString());
    libraryMutex.lock();

    try {
      libraryMutationTransaction.execute(
          identity, new Intent.RemoveLibrary(libraryId), () -> removeLibrary(libraryId));
    } finally {
      libraryMutex.unlock();
    }
  }

  private void removeLibrary(UUID libraryId) {
    var library = findLibraryOrThrow(libraryId);
    rejectIfScanning(library);
    rejectIfRefreshing(library);

    var mediaFiles = mediaFileRepository.findByLibraryId(libraryId);
    var mediaFileIds = extractMediaFileIds(mediaFiles);

    deleteLibraryContent(libraryId, mediaFiles);
    libraryRepository.delete(library);

    eventPublisher.publishEvent(new LibraryRemovedEvent(library.getFilepathUri(), mediaFileIds));
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

  private void rejectIfRefreshing(Library library) {
    if (library.getStatus() == LibraryStatus.REFRESHING) {
      throw new LibraryRefreshInProgressException(library.getId());
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

  public void refreshLibrary(UUID libraryId) {
    refreshLibrary(libraryId, libraryRefreshService::refreshLibrary);
  }

  public void refreshLibrary(UUID libraryId, ImageRefreshMode imageRefreshMode) {
    refreshLibrary(
        libraryId, library -> libraryRefreshService.refreshLibrary(library, imageRefreshMode));
  }

  private void refreshLibrary(UUID libraryId, Consumer<Library> refresh) {
    if (!activeRefreshes.add(libraryId)) {
      throw new LibraryRefreshInProgressException(libraryId);
    }

    try {
      var library = transitionToRefreshing(libraryId);
      var startTime = Instant.now();

      try {
        refresh.accept(library);
        completeRefreshSuccessfully(library, startTime);
      } catch (Exception ex) {
        log.error("Refresh failed for library '{}'", library.getName(), ex);
        completeRefreshWithFailure(library);
      }
    } finally {
      eventPublisher.publishEvent(new RefreshEndedEvent(libraryId));
      activeRefreshes.remove(libraryId);
    }
  }

  private Library transitionToRefreshing(UUID libraryId) {
    var libraryMutex = mutexFactory.getMutex(libraryId.toString());
    libraryMutex.lock();

    try {
      var library = findLibraryOrThrow(libraryId);

      if (library.getStatus() == LibraryStatus.SCANNING) {
        throw new LibraryScanInProgressException(libraryId);
      }
      if (library.getStatus() == LibraryStatus.REFRESHING) {
        throw new LibraryRefreshInProgressException(libraryId);
      }

      log.info("Starting {} library refresh.", library.getName());

      library.setStatus(LibraryStatus.REFRESHING);
      libraryRepository.save(library);

      return library;
    } finally {
      libraryMutex.unlock();
    }
  }

  private void completeRefreshSuccessfully(Library library, Instant startTime) {
    var elapsedSeconds = Duration.between(startTime, Instant.now()).getSeconds();

    library.setStatus(LibraryStatus.HEALTHY);
    libraryRepository.save(library);

    log.info("Finished {} library refresh in {} seconds.", library.getName(), elapsedSeconds);
  }

  private void completeRefreshWithFailure(Library library) {
    library.setStatus(LibraryStatus.UNHEALTHY);
    libraryRepository.save(library);
  }

  public void processDiscoveredFile(UUID libraryId, Path path) {
    var library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(() -> new LibraryNotFoundException(libraryId));

    if (processFile(library, path)) {
      eventPublisher.publishEvent(new ItemProcessedEvent(libraryId));
    }
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
        completeScanWithFailure(library, e);
      }
    } finally {
      eventPublisher.publishEvent(new ScanEndedEvent(libraryId));
      activeScans.remove(libraryId);
    }
  }

  private void walkAndProcessFiles(Library library) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var stream = Files.walk(FilepathCodec.decode(fileSystem, library.getFilepathUri()))) {

      var tasks =
          stream
              .filter(Files::isRegularFile)
              .filter(file -> !ignoredFileValidator.shouldIgnore(file))
              .map(file -> executor.submit(() -> processFile(library, file)))
              .toList();
      awaitFileProcessing(library, tasks);

    } catch (IOException | UncheckedIOException | SecurityException | InvalidPathException e) {
      throw new LibraryScanFailedException(library.getName(), e);
    }
  }

  private static void awaitFileProcessing(Library library, List<? extends Future<?>> tasks) {
    var failures = new ArrayList<Throwable>();

    for (var task : tasks) {
      try {
        task.get();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new LibraryScanFailedException(library.getName(), exception);
      } catch (ExecutionException exception) {
        failures.add(exception.getCause());
      }
    }

    if (failures.isEmpty()) {
      return;
    }

    var scanFailure =
        new LibraryScanFailedException(library.getName(), failures.size(), failures.getFirst());
    failures.stream().skip(1).forEach(scanFailure::addSuppressed);
    throw scanFailure;
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

    log.error("Failed {} library scan.", library.getName(), cause);
  }

  private Library transitionToScanning(UUID libraryId) {
    var libraryMutex = mutexFactory.getMutex(libraryId.toString());
    libraryMutex.lock();

    try {
      var library =
          libraryRepository
              .findById(libraryId)
              .orElseThrow(() -> new LibraryNotFoundException(libraryId));

      if (library.getStatus() == LibraryStatus.SCANNING) {
        throw new LibraryScanInProgressException(libraryId);
      }
      if (library.getStatus() == LibraryStatus.REFRESHING) {
        throw new LibraryRefreshInProgressException(libraryId);
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

  private boolean processFile(Library library, Path path) {

    if (!hasSupportedExtension(path)) {
      log.warn(
          "Unsupported file extension: {} for filepath {}.",
          getExtension(path),
          path.toAbsolutePath());
      return false;
    }

    var mediaFile = probeFile(library, path);

    if (isAlreadyMatched(mediaFile)) {
      return false;
    }

    switch (library.getType()) {
      case MOVIE -> movieFileProcessor.process(library, mediaFile);
      case SERIES -> seriesFileProcessor.process(library, mediaFile);
      default -> throw new IllegalStateException("Unsupported media type: " + library.getType());
    }

    return true;
  }

  private boolean hasSupportedExtension(Path path) {
    return videoExtensionValidator.validate(getExtension(path));
  }

  private String getExtension(Path path) {
    return FilenameUtils.getExtension(path.getFileName().toString());
  }

  private MediaFile probeFile(Library library, Path path) {
    var absoluteFilepath = FilepathCodec.encode(path);
    var filepathMutex = mutexFactory.getMutex(absoluteFilepath);

    filepathMutex.lock();
    try {
      var optionalMediaFile = mediaFileRepository.findFirstByFilepathUri(absoluteFilepath);

      if (optionalMediaFile.isEmpty()) {
        return createNewMediaFile(library, path, absoluteFilepath);
      }

      var mediaFile = optionalMediaFile.orElseThrow();
      var filename = FilepathCodec.filenameOf(absoluteFilepath);
      if (!filename.equals(mediaFile.getFilename())) {
        mediaFile.setFilename(filename);
        mediaFileRepository.save(mediaFile);
      }

      log.info("MediaFile id: '{}' already exists, not adding again.", mediaFile.getId());
      return mediaFile;
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
            .filename(FilepathCodec.filenameOf(absoluteFilepath))
            .filepathUri(absoluteFilepath)
            .size(fileSize)
            .libraryId(library.getId())
            .build());
  }

  private boolean isAlreadyMatched(MediaFile file) {
    return file.getStatus().equals(MediaFileStatus.MATCHED);
  }
}
