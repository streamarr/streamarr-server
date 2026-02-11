package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.library.events.ScanCompletedEvent;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanedMediaFileCleanupService {

  private final LibraryRepository libraryRepository;
  private final MediaFileRepository mediaFileRepository;
  private final MovieService movieService;
  private final FileSystem fileSystem;

  @Transactional
  @EventListener
  public void onScanCompleted(ScanCompletedEvent event) {
    try {
      var library =
          libraryRepository
              .findById(event.libraryId())
              .orElseThrow(() -> new LibraryNotFoundException(event.libraryId()));
      cleanupOrphanedFiles(library);
    } catch (LibraryNotFoundException e) {
      log.warn("Library {} was deleted before orphaned file cleanup could run.", event.libraryId());
    }
  }

  public void cleanupOrphanedFiles(Library library) {
    var mediaFiles = mediaFileRepository.findByLibraryId(library.getId());

    var orphanedFiles = mediaFiles.stream().filter(file -> !isFileStillOnDisk(file)).toList();

    if (orphanedFiles.isEmpty()) {
      return;
    }

    var affectedMovieIds =
        orphanedFiles.stream()
            .map(MediaFile::getMediaId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

    mediaFileRepository.deleteAll(orphanedFiles);

    deleteMoviesWithNoRemainingFiles(affectedMovieIds);

    log.info(
        "Removed {} orphaned media file(s) from {} library.",
        orphanedFiles.size(),
        library.getName());
  }

  private boolean isFileStillOnDisk(MediaFile file) {
    return Files.exists(fileSystem.getPath(file.getFilepath()));
  }

  private void deleteMoviesWithNoRemainingFiles(Set<UUID> movieIds) {
    if (movieIds.isEmpty()) {
      return;
    }

    var mediaIdsWithRemainingFiles = mediaFileRepository.findDistinctMediaIdsByMediaIdIn(movieIds);

    for (var movieId : movieIds) {
      if (!mediaIdsWithRemainingFiles.contains(movieId)) {
        movieService.deleteMovieById(movieId);
        log.info("Deleted Movie id: {} — no remaining media files.", movieId);
      }
    }
  }
}
