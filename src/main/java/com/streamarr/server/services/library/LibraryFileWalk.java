package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.exceptions.LibraryScanFailedException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.ArtworkRunWaiter;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.concurrency.AdmittedTaskReport;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;

/**
 * Walks a library's files during a scan and matches each media file, and matches a single file that
 * the file watcher discovered.
 */
@Slf4j
@Service
public class LibraryFileWalk {

  private final IgnoredFileValidator ignoredFileValidator;
  private final VideoExtensionValidator videoExtensionValidator;
  private final MovieFileProcessor movieFileProcessor;
  private final SeriesFileProcessor seriesFileProcessor;
  private final MediaFileRepository mediaFileRepository;
  private final FileSystem fileSystem;
  private final FileDiscoveryRuns fileDiscoveryRuns;
  private final LibraryOperationAdmissions libraryOperationAdmissions;
  private final ArtworkRunWaiter artworkRunWaiter;
  private final MutexFactory<String> mutexFactory;

  @Builder
  public LibraryFileWalk(
      IgnoredFileValidator ignoredFileValidator,
      VideoExtensionValidator videoExtensionValidator,
      MovieFileProcessor movieFileProcessor,
      SeriesFileProcessor seriesFileProcessor,
      MediaFileRepository mediaFileRepository,
      FileSystem fileSystem,
      FileDiscoveryRuns fileDiscoveryRuns,
      LibraryOperationAdmissions libraryOperationAdmissions,
      ArtworkRunWaiter artworkRunWaiter,
      MutexFactoryProvider mutexFactoryProvider) {
    this.ignoredFileValidator = ignoredFileValidator;
    this.videoExtensionValidator = videoExtensionValidator;
    this.movieFileProcessor = movieFileProcessor;
    this.seriesFileProcessor = seriesFileProcessor;
    this.mediaFileRepository = mediaFileRepository;
    this.fileSystem = fileSystem;
    this.fileDiscoveryRuns = fileDiscoveryRuns;
    this.libraryOperationAdmissions = libraryOperationAdmissions;
    this.artworkRunWaiter = artworkRunWaiter;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  /**
   * Walks the library and matches each of its files, then returns the closed discovery whose
   * results the scan waits for. Each file's task is admitted through the scan's adaptive limit
   * before the walk pulls the next file. A task that throws ends admission, and so does the
   * admission end: files that were not admitted yet are not processed in this scan.
   *
   * @throws LibraryScanFailedException if the library cannot be walked or a file's task fails
   * @throws InterruptedException if the walk is interrupted; the admitted tasks are interrupted and
   *     joined first
   */
  public FileDiscovery discoverFiles(
      Library library, UUID libraryOperationId, AdmissionEnd admissionEnd)
      throws InterruptedException {
    var discovery = fileDiscoveryRuns.openScan(library, libraryOperationId);
    try (discovery;
        var admission = libraryOperationAdmissions.openScanAdmission(library);
        var stream = Files.walk(FilepathCodec.decode(fileSystem, library.getFilepathUri()))) {

      var files =
          stream
              .flatMap(file -> regularFile(file).stream())
              .filter(listed -> !ignoredFileValidator.shouldIgnore(listed.path()))
              .filter(listed -> keepSupportedOrWarn(listed.path()))
              .iterator();
      var failures =
          admission.forkAdmittedTasks(
              admissionEnd.untilReached(files),
              listed -> processAdmittedFile(discovery, listed),
              SystemWork.newThreadFactoryRunningAsSystemActor("library-scan-file-"));
      throwIfAnyFileFailed(library, failures);

    } catch (IOException | UncheckedIOException | SecurityException | InvalidPathException e) {
      throw new LibraryScanFailedException(library.getName(), e);
    }

    return discovery;
  }

  /**
   * Tries to match a file that the file watcher discovered, and waits for nothing.
   *
   * @return false when the file has an unsupported extension or was already matched, so nothing was
   *     processed; true otherwise, whether or not matching then succeeded
   */
  public boolean tryProcessDiscoveredFile(Library library, Path path) {
    if (!keepSupportedOrWarn(path)) {
      return false;
    }

    try (var discovery = fileDiscoveryRuns.open("file discovery in", library)) {
      var outcome = processFile(discovery, path, discovery.probeRun()::request);
      return !(outcome instanceof MatchingOutcome.SkippedAsMatched);
    }
  }

  // Like Files.isRegularFile, this follows links and skips a file whose attributes cannot be read.
  private static Optional<ListedFile> regularFile(Path path) {
    try {
      var attributes = Files.readAttributes(path, BasicFileAttributes.class);
      if (!attributes.isRegularFile()) {
        return Optional.empty();
      }

      return Optional.of(new ListedFile(path, SourceFileSnapshot.of(attributes)));
    } catch (IOException _) {
      return Optional.empty();
    }
  }

  // The task keeps its admission until the primary artwork it requested has recorded results, so
  // its latency includes that artwork.
  private AdmittedTaskReport processAdmittedFile(FileDiscovery discovery, ListedFile listed)
      throws InterruptedException {
    var taskDiscovery =
        new FileDiscovery(
            discovery.library(), discovery.artworkRun().openTaskRun(), discovery.probeRun());
    MatchingOutcome outcome;
    try (taskDiscovery) {
      outcome = processListedFile(taskDiscovery, listed);
    }

    if (!artworkRunWaiter.tryAwaitTaskResults(taskDiscovery.artworkRun())) {
      return AdmittedTaskReport.DROP;
    }

    return AdmissionReports.forMatching(outcome);
  }

  // The probe is requested with the snapshot the listing observed, so a source that becomes
  // unreadable afterwards fails its probe attempt rather than the scan.
  private MatchingOutcome processListedFile(FileDiscovery discovery, ListedFile listed) {
    return processFile(
        discovery,
        listed.path(),
        mediaFileId -> discovery.probeRun().request(mediaFileId, listed.snapshot()));
  }

  private record ListedFile(Path path, SourceFileSnapshot snapshot) {}

  private static void throwIfAnyFileFailed(Library library, List<Throwable> failures) {
    if (failures.isEmpty()) {
      return;
    }

    var scanFailure =
        new LibraryScanFailedException(library.getName(), failures.size(), failures.getFirst());
    failures.stream().skip(1).forEach(scanFailure::addSuppressed);
    throw scanFailure;
  }

  private MatchingOutcome processFile(
      FileDiscovery discovery, Path path, Consumer<UUID> requestProbe) {
    var library = discovery.library();
    var mediaFile = findOrCreateMediaFile(library, path);
    requestProbe.accept(mediaFile.getId());

    if (isAlreadyMatched(mediaFile)) {
      return new MatchingOutcome.SkippedAsMatched();
    }

    return switch (library.getType()) {
      case MOVIE -> movieFileProcessor.process(discovery, mediaFile);
      case SERIES -> seriesFileProcessor.process(discovery, mediaFile);
      default -> throw new IllegalStateException("Unsupported media type: " + library.getType());
    };
  }

  // Files with other extensions are not media files, so they get no task and no media_file row.
  private boolean keepSupportedOrWarn(Path path) {
    if (hasSupportedExtension(path)) {
      return true;
    }

    log.warn(
        "Unsupported file extension: {} for filepath {}.",
        getExtension(path),
        path.toAbsolutePath());
    return false;
  }

  private boolean hasSupportedExtension(Path path) {
    return videoExtensionValidator.validate(getExtension(path));
  }

  private String getExtension(Path path) {
    return FilenameUtils.getExtension(path.getFileName().toString());
  }

  private MediaFile findOrCreateMediaFile(Library library, Path path) {
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
