package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryOperationNotScheduledException;
import com.streamarr.server.exceptions.LibraryOperationNotStartedException;
import com.streamarr.server.repositories.LibraryMetadataRepository;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.events.library.ItemProcessedEvent;
import com.streamarr.server.services.events.library.LibraryAddedEvent;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Builder
@RequiredArgsConstructor
public class LibraryManagementService {

  private final LibraryFileWalk libraryFileWalk;
  private final LibraryRepository libraryRepository;
  private final LibraryMetadataRepository libraryMetadataRepository;
  private final MediaFileRepository mediaFileRepository;
  private final MovieService movieService;
  private final SeriesService seriesService;
  private final ApplicationEventPublisher eventPublisher;
  private final FileSystem fileSystem;
  private final LibraryMutationTransaction libraryMutationTransaction;
  private final MutationTransactions mutationTransactions;
  private final LibraryOperationRepository libraryOperations;
  private final LibraryOperationService libraryOperationService;

  public List<LibraryMetadata> getAlphabetIndex(UUID libraryId) {
    return libraryMetadataRepository.findByLibraryIdOrderByLetterAsc(libraryId);
  }

  /**
   * Decides before it writes: name and path are validated first, then one transaction compares the
   * path with every library's path, inserts the library and stores its first scan. The library is
   * returned {@code SCANNING}, and {@link LibraryAddedEvent} reaches the AFTER_COMMIT listeners
   * that start watching. Concurrent adds take one lock before they read the libraries, so each add
   * sees the library that an earlier add committed. A refused add stores no scan and performs no
   * side effect.
   *
   * @throws LibraryOperationNotStartedException when the first scan cannot be scheduled, so the
   *     transaction adds no library
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

    var requested = RequestedLibraryPath.probe(path.orElseThrow());
    var libraryToSave =
        library.toBuilder()
            .filepathUri(FilepathCodec.encode(requested.path()))
            .status(LibraryStatus.HEALTHY)
            .build();
    try {
      return insertWithFirstScan(identity, libraryToSave, requested);
    } catch (LibraryOperationNotScheduledException e) {
      throw new LibraryOperationNotStartedException(e);
    }
  }

  private Outcome<Library, AddLibraryRejection> insertWithFirstScan(
      AuthenticatedIdentity identity, Library libraryToSave, RequestedLibraryPath requested) {
    return mutationTransactions.write(
        () ->
            libraryMutationTransaction.execute(
                identity,
                new Intent.AddLibrary(),
                () -> {
                  refuseConflictingPath(requested);
                  // The insert is flushed first, because the first scan locks the library's row.
                  var saved = libraryRepository.saveAndFlush(libraryToSave);
                  eventPublisher.publishEvent(
                      new LibraryAddedEvent(saved.getId(), saved.getFilepathUri()));
                  return libraryOperationService.storeFirstLibraryScan(saved.getId());
                }),
        _ -> Optional.empty());
  }

  private void refuseConflictingPath(RequestedLibraryPath requested) {
    var conflicts =
        LibraryPathConflicts.conflictsOf(
            fileSystem, requested, libraryRepository.lockLibraryPathsForAddLibrary());
    if (!conflicts.isEmpty()) {
      throw MutationRejection.ofReasons(conflicts);
    }
  }

  private Optional<Path> validatedPath(String rawFilepath, List<AddLibraryRejection> rejections) {
    if (rawFilepath == null || rawFilepath.isBlank()) {
      rejections.add(new AddLibraryRejection.PathRequired());
      return Optional.empty();
    }

    try {
      var path = fileSystem.getPath(rawFilepath).toAbsolutePath().normalize();
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

  /**
   * Removes the library and its content in one transaction that holds the library's row, so no scan
   * or refresh can start before the removal commits.
   *
   * @return the removed library's id, or why the library cannot be removed now
   */
  public Outcome<UUID, LibraryOperationRejection> removeLibrary(
      AuthenticatedIdentity identity, UUID libraryId) {
    return mutationTransactions.write(
        () ->
            libraryMutationTransaction.execute(
                identity,
                new Intent.RemoveLibrary(libraryId),
                () -> {
                  libraryOperations
                      .lockLibraryForLibraryRemoval(libraryId)
                      .ifPresent(
                          rejection -> {
                            throw new MutationRejection(rejection);
                          });
                  removeLockedLibrary(libraryId);
                  return libraryId;
                }),
        _ -> Optional.empty());
  }

  private void removeLockedLibrary(UUID libraryId) {
    var library = libraryRepository.findById(libraryId).orElseThrow();
    var mediaFiles = mediaFileRepository.findByLibraryId(libraryId);
    var mediaFileIds = extractMediaFileIds(mediaFiles);

    deleteLibraryContent(libraryId, mediaFiles);
    libraryRepository.delete(library);

    eventPublisher.publishEvent(
        new LibraryRemovedEvent(libraryId, library.getFilepathUri(), mediaFileIds));
  }

  private Set<UUID> extractMediaFileIds(List<MediaFile> mediaFiles) {
    return mediaFiles.stream().map(MediaFile::getId).collect(Collectors.toSet());
  }

  private void deleteLibraryContent(UUID libraryId, List<MediaFile> mediaFiles) {
    movieService.deleteByLibraryId(libraryId);
    seriesService.deleteByLibraryId(libraryId);
    mediaFileRepository.deleteAll(mediaFiles);
  }

  /** Processes a file that the file watcher discovered, as the System actor. */
  public void processDiscoveredFile(UUID libraryId, Path path) {
    SystemWork.runAsSystemActor(() -> processDiscoveredFileInLibrary(libraryId, path));
  }

  private void processDiscoveredFileInLibrary(UUID libraryId, Path path) {
    var library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(() -> new LibraryNotFoundException(libraryId));

    if (libraryFileWalk.tryProcessDiscoveredFile(library, path)) {
      eventPublisher.publishEvent(new ItemProcessedEvent(libraryId));
    }
  }
}
