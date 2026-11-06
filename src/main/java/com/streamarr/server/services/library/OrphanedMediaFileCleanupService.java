package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.events.library.CollectablesChangedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Gatherers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanedMediaFileCleanupService {

  // PostgreSQL's protocol binds at most 65,535 parameters to one query.
  private static final int MEDIA_FILE_IDS_PER_QUERY = 10_000;

  private final MediaFileRepository mediaFileRepository;
  private final MovieService movieService;
  private final FileSystem fileSystem;
  private final ApplicationEventPublisher events;

  /**
   * Returns the ids of the library's media files that no longer exist on disk. A media file whose
   * path cannot be decoded counts as missing.
   */
  public List<UUID> findMissingMediaFileIds(Library library) {
    return mediaFileRepository.findByLibraryId(library.getId()).stream()
        .filter(file -> !isFileStillOnDisk(file))
        .map(MediaFile::getId)
        .toList();
  }

  /**
   * Removes the given missing media files of the library, in the caller's transaction, and deletes
   * each movie that has no media file left. A media file that is already gone is skipped.
   */
  public void removeMissingMediaFiles(Library library, List<UUID> missingMediaFileIds) {
    var orphanedFiles =
        missingMediaFileIds.stream()
            .gather(Gatherers.windowFixed(MEDIA_FILE_IDS_PER_QUERY))
            .flatMap(batch -> mediaFileRepository.findAllById(batch).stream())
            .toList();

    if (orphanedFiles.isEmpty()) {
      return;
    }

    var affectedMovieIds =
        orphanedFiles.stream()
            .map(MediaFile::getMediaId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

    mediaFileRepository.deleteAll(orphanedFiles);

    var changedItemIds = deleteMoviesWithNoRemainingFiles(affectedMovieIds);
    if (!changedItemIds.isEmpty()) {
      events.publishEvent(new CollectablesChangedEvent(changedItemIds));
    }

    log.info(
        "Removed {} orphaned media file(s) from {} library.",
        orphanedFiles.size(),
        library.getName());
  }

  private boolean isFileStillOnDisk(MediaFile file) {
    Path path;

    try {
      path = FilepathCodec.decode(fileSystem, file.getFilepathUri());
    } catch (IllegalArgumentException _) {
      log.warn("MediaFile id: {} has unmappable filepath — treating as orphaned.", file.getId());
      return false;
    }

    return Files.exists(path);
  }

  private Set<UUID> deleteMoviesWithNoRemainingFiles(Set<UUID> movieIds) {
    if (movieIds.isEmpty()) {
      return Set.of();
    }

    var mediaIdsWithRemainingFiles = mediaFileRepository.findDistinctMediaIdsByMediaIdIn(movieIds);
    var remainingIds = new HashSet<>(movieIds);

    for (var movieId : movieIds) {
      if (mediaIdsWithRemainingFiles.contains(movieId)
          || !movieService.tryDeleteMovieById(movieId)) {
        continue;
      }

      remainingIds.remove(movieId);
      log.info("Deleted Movie id: {} — no remaining media files.", movieId);
    }

    return remainingIds;
  }
}
