package com.streamarr.server.services.library;

import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.streaming.StreamSessionTerminalReason;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryRefreshInProgressException;
import com.streamarr.server.exceptions.LibraryScanInProgressException;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.DeletionIntentEntry;
import com.streamarr.server.repositories.media.LibraryDeletionTarget;
import com.streamarr.server.repositories.media.MediaFileDeletionTarget;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MediaParentDeletionRepository;
import com.streamarr.server.repositories.streaming.MediaStreamTermination;
import com.streamarr.server.repositories.streaming.StreamSessionEnforcementRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.library.events.LibraryRemovedEvent;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class MediaParentDeletionTransactions {

  private final MediaParentDeletionRepository deletionRepository;
  private final StreamSessionEnforcementRepository streamRepository;
  private final MediaFileRepository mediaFileRepository;
  private final MovieService movieService;
  private final SeriesService seriesService;
  private final LibraryRepository libraryRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  @Transactional
  public LibraryDeletionPlan prepareLibraryDeletion(UUID libraryId) {
    var target =
        deletionRepository
            .prepareLibraryDeletion(libraryId)
            .orElseThrow(() -> new LibraryNotFoundException(libraryId));
    rejectDeletionWhileBusy(target.libraryId(), target.status());

    terminalizeStreams(target);
    return LibraryDeletionPlan.builder()
        .target(target)
        .streamSessionIds(deletionRepository.findReferencingStreamIds(target.mediaFileIds()))
        .build();
  }

  @Transactional
  public LibraryDeletionPlan resumeLibraryDeletion(UUID libraryId) {
    var target =
        deletionRepository
            .resumeLibraryDeletion(libraryId)
            .orElseThrow(() -> new LibraryNotFoundException(libraryId));
    rejectDeletionWhileBusy(target.libraryId(), target.status());
    terminalizeStreams(target);
    return LibraryDeletionPlan.builder()
        .target(target)
        .streamSessionIds(deletionRepository.findReferencingStreamIds(target.mediaFileIds()))
        .build();
  }

  @Transactional
  public MediaFileDeletionPlan prepareMediaFileDeletions(UUID libraryId, Set<UUID> mediaFileIds) {
    var targets = deletionRepository.prepareMediaFileDeletions(libraryId, mediaFileIds);
    return mediaFileDeletionPlan(targets);
  }

  @Transactional
  public MediaFileDeletionPlan resumeMediaFileDeletion(UUID mediaFileId) {
    var target = deletionRepository.resumeMediaFileDeletion(mediaFileId);
    return mediaFileDeletionPlan(target.stream().toList());
  }

  @Transactional
  public boolean finalizeLibraryDeletion(UUID libraryId) {
    var target = deletionRepository.resumeLibraryDeletion(libraryId);
    if (target.isEmpty()) {
      return false;
    }

    var library = target.orElseThrow();
    rejectDeletionWhileBusy(library.libraryId(), library.status());
    if (deletionRepository.hasReferencingStreams(library.mediaFileIds())) {
      return false;
    }

    mediaFileRepository.deleteAllByIdInBatch(library.mediaFileIds());
    movieService.deleteByLibraryId(libraryId);
    seriesService.deleteByLibraryId(libraryId);
    libraryRepository.deleteById(libraryId);
    libraryRepository.flush();
    eventPublisher.publishEvent(
        new LibraryRemovedEvent(library.filepathUri(), Set.copyOf(library.mediaFileIds())));
    return true;
  }

  @Transactional
  public boolean finalizeMediaFileDeletion(UUID mediaFileId) {
    var target = deletionRepository.resumeMediaFileDeletion(mediaFileId);
    if (target.isEmpty() || deletionRepository.hasReferencingStreams(Set.of(mediaFileId))) {
      return false;
    }

    var media = target.orElseThrow();
    mediaFileRepository.deleteById(mediaFileId);
    mediaFileRepository.flush();
    deleteEmptyMovie(media);
    return true;
  }

  @Transactional(readOnly = true)
  public List<DeletionIntentEntry> findPendingLibraryDeletions(int limit) {
    return deletionRepository.findLibraryDeletionIntents(limit);
  }

  @Transactional(readOnly = true)
  public List<DeletionIntentEntry> findPendingLibraryDeletionsAfter(
      DeletionIntentEntry cursor, int limit) {
    return deletionRepository.findLibraryDeletionIntentsAfter(cursor, limit);
  }

  @Transactional(readOnly = true)
  public List<DeletionIntentEntry> findPendingStandaloneMediaFileDeletions(int limit) {
    return deletionRepository.findStandaloneMediaFileDeletionIntents(limit);
  }

  @Transactional(readOnly = true)
  public List<DeletionIntentEntry> findPendingStandaloneMediaFileDeletionsAfter(
      DeletionIntentEntry cursor, int limit) {
    return deletionRepository.findStandaloneMediaFileDeletionIntentsAfter(cursor, limit);
  }

  @Transactional(readOnly = true)
  public boolean isLibraryDeletionPending(UUID libraryId) {
    return deletionRepository.hasLibraryDeletionIntent(libraryId);
  }

  private MediaFileDeletionPlan mediaFileDeletionPlan(List<MediaFileDeletionTarget> targets) {
    var mediaFileIds =
        targets.stream().map(MediaFileDeletionTarget::mediaFileId).collect(Collectors.toSet());
    streamRepository.terminalizeByMediaFiles(
        MediaStreamTermination.builder()
            .mediaFileIds(mediaFileIds)
            .reason(StreamSessionTerminalReason.SOURCE_DELETED)
            .terminalAt(clock.instant())
            .build());
    return MediaFileDeletionPlan.builder()
        .targets(targets)
        .streamSessionIds(deletionRepository.findReferencingStreamIds(mediaFileIds))
        .build();
  }

  private void deleteEmptyMovie(MediaFileDeletionTarget target) {
    var mediaId = target.mediaId();
    if (mediaId == null || !mediaFileRepository.findByMediaId(mediaId).isEmpty()) {
      return;
    }
    movieService.findById(mediaId).ifPresent(_ -> movieService.deleteMovieById(mediaId));
  }

  private void terminalizeStreams(LibraryDeletionTarget target) {
    streamRepository.terminalizeByMediaFiles(
        MediaStreamTermination.builder()
            .mediaFileIds(Set.copyOf(target.mediaFileIds()))
            .reason(StreamSessionTerminalReason.SOURCE_DELETED)
            .terminalAt(clock.instant())
            .build());
  }

  private void rejectDeletionWhileBusy(UUID libraryId, LibraryStatus status) {
    if (status == LibraryStatus.SCANNING) {
      throw new LibraryScanInProgressException(libraryId);
    }
    if (status == LibraryStatus.REFRESHING) {
      throw new LibraryRefreshInProgressException(libraryId);
    }
  }
}
