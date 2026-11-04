package com.streamarr.server.fakes;

import com.streamarr.server.domain.SkippedFile;
import com.streamarr.server.repositories.SkippedFileRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FakeSkippedFileRepository implements SkippedFileRepository {

  private final Map<Key, SkippedFile> database = new ConcurrentHashMap<>();

  @Override
  public void save(SkippedFile skippedFile) {
    database.put(new Key(skippedFile.libraryId(), skippedFile.filepathUri()), skippedFile);
  }

  @Override
  public void deleteSkippedFilesOfOtherLibraryOperations(UUID libraryId, UUID libraryOperationId) {
    database
        .values()
        .removeIf(
            skippedFile ->
                skippedFile.libraryId().equals(libraryId)
                    && !skippedFile.libraryOperationId().equals(libraryOperationId));
  }

  /** The library's skipped files in filepath order. */
  public List<SkippedFile> skippedFilesOf(UUID libraryId) {
    return database.values().stream()
        .filter(skippedFile -> skippedFile.libraryId().equals(libraryId))
        .sorted(Comparator.comparing(SkippedFile::filepathUri))
        .toList();
  }

  private record Key(UUID libraryId, String filepathUri) {}
}
