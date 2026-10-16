package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryFolderProblem;
import com.streamarr.server.exceptions.LibraryFolderCheckFailedException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Fails a scan whose library folder does not exist, cannot be read, or is empty while Streamarr
 * still records files for the library. An unmounted share leaves exactly such a folder, and a scan
 * that went on would remove every item of the library.
 */
@Service
@RequiredArgsConstructor
public class LibraryFolderCheck {

  static final String FAILURES_METRIC = "streamarr.library.folder_check.failures";

  private final FileSystem fileSystem;
  private final MediaFileRepository mediaFileRepository;
  private final MeterRegistry meterRegistry;

  /**
   * @throws LibraryFolderCheckFailedException if the library folder fails the check
   */
  public void requirePassed(Library library) {
    var problem = findProblem(library);
    if (problem.isEmpty()) {
      return;
    }

    meterRegistry.counter(FAILURES_METRIC, "reason", problem.get().metricTag()).increment();
    throw new LibraryFolderCheckFailedException(library.getName(), problem.get());
  }

  private Optional<LibraryFolderProblem> findProblem(Library library) {
    Path folder;
    try {
      folder = FilepathCodec.decode(fileSystem, library.getFilepathUri());
    } catch (IllegalArgumentException _) {
      return Optional.of(LibraryFolderProblem.UNREADABLE);
    }

    try (var entries = Files.newDirectoryStream(folder)) {
      if (entries.iterator().hasNext()) {
        return Optional.empty();
      }
    } catch (NoSuchFileException | NotDirectoryException _) {
      return Optional.of(LibraryFolderProblem.MISSING);
    } catch (IOException | SecurityException _) {
      return Optional.of(LibraryFolderProblem.UNREADABLE);
    }

    if (mediaFileRepository.existsByLibraryId(library.getId())) {
      return Optional.of(LibraryFolderProblem.EMPTY);
    }

    return Optional.empty();
  }
}
