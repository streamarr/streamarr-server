package com.streamarr.server.fakes;

import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryRefreshInProgressException;
import com.streamarr.server.exceptions.LibraryScanInProgressException;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.library.MediaParentDeletionService;
import com.streamarr.server.services.library.events.LibraryRemovedEvent;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import org.springframework.context.ApplicationEventPublisher;

@Builder
public class FakeMediaParentDeletionService implements MediaParentDeletionService {

  private final LibraryRepository libraryRepository;
  private final MediaFileRepository mediaFileRepository;
  private final MovieService movieService;
  private final SeriesService seriesService;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  public void deleteLibrary(UUID libraryId) {
    var library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(() -> new LibraryNotFoundException(libraryId));
    rejectWhileBusy(libraryId, library.getStatus());
    var mediaFiles = mediaFileRepository.findByLibraryId(libraryId);
    var mediaFileIds = mediaFiles.stream().map(file -> file.getId()).collect(Collectors.toSet());

    movieService.deleteByLibraryId(libraryId);
    seriesService.deleteByLibraryId(libraryId);
    mediaFileRepository.deleteAll(mediaFiles);
    libraryRepository.delete(library);
    eventPublisher.publishEvent(
        new LibraryRemovedEvent(library.getFilepathUri(), Set.copyOf(mediaFileIds)));
  }

  @Override
  public void resumeLibraryDeletion(UUID libraryId) {
    deleteLibrary(libraryId);
  }

  @Override
  public void deleteMediaFiles(UUID libraryId, Set<UUID> mediaFileIds) {
    var mediaFiles =
        mediaFileIds.stream().flatMap(id -> mediaFileRepository.findById(id).stream()).toList();
    var collectableIds =
        mediaFiles.stream()
            .map(file -> file.getMediaId())
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    mediaFileRepository.deleteAll(mediaFiles);
    for (var collectableId : collectableIds) {
      if (mediaFileRepository.findByMediaId(collectableId).isEmpty()) {
        movieService
            .findById(collectableId)
            .ifPresent(_ -> movieService.deleteMovieById(collectableId));
      }
    }
  }

  @Override
  public void resumeMediaFileDeletion(UUID mediaFileId) {
    mediaFileRepository
        .findById(mediaFileId)
        .ifPresent(file -> deleteMediaFiles(file.getLibraryId(), Set.of(mediaFileId)));
  }

  @Override
  public boolean isLibraryDeletionPending(UUID libraryId) {
    return false;
  }

  private void rejectWhileBusy(UUID libraryId, LibraryStatus status) {
    if (status == LibraryStatus.SCANNING) {
      throw new LibraryScanInProgressException(libraryId);
    }
    if (status == LibraryStatus.REFRESHING) {
      throw new LibraryRefreshInProgressException(libraryId);
    }
  }
}
