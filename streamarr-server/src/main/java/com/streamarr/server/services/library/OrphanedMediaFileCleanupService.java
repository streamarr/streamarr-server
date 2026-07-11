package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.library.events.ScanCompletedEvent;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanedMediaFileCleanupService {

  private final LibraryRepository libraryRepository;
  private final MediaFileRepository mediaFileRepository;
  private final MediaParentDeletionService deletionService;
  private final FileSystem fileSystem;

  @EventListener
  public void onScanCompleted(ScanCompletedEvent event) {
    try {
      var library =
          libraryRepository
              .findById(event.libraryId())
              .orElseThrow(() -> new LibraryNotFoundException(event.libraryId()));
      cleanupOrphanedFiles(library);
    } catch (LibraryNotFoundException _) {
      log.warn("Library {} was deleted before orphaned file cleanup could run.", event.libraryId());
    } catch (Exception e) {
      log.error("Orphaned file cleanup failed for library: {}", event.libraryId(), e);
    }
  }

  public void cleanupOrphanedFiles(Library library) {
    var mediaFiles = mediaFileRepository.findByLibraryId(library.getId());

    var orphanedFiles = mediaFiles.stream().filter(file -> !isFileStillOnDisk(file)).toList();

    if (orphanedFiles.isEmpty()) {
      return;
    }

    var orphanedFileIds = orphanedFiles.stream().map(MediaFile::getId).collect(Collectors.toSet());
    deletionService.deleteMediaFiles(library.getId(), orphanedFileIds);

    log.info(
        "Requested removal of {} orphaned media file(s) from {} library.",
        orphanedFiles.size(),
        library.getName());
  }

  private boolean isFileStillOnDisk(MediaFile file) {
    try {
      var path = FilepathCodec.decode(fileSystem, file.getFilepathUri());
      return Files.exists(path);
    } catch (InvalidPathException | SecurityException _) {
      log.warn("MediaFile id: {} has unmappable filepath — treating as orphaned.", file.getId());
      return false;
    }
  }
}
