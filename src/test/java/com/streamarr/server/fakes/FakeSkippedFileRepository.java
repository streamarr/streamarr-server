package com.streamarr.server.fakes;

import com.streamarr.server.domain.SkippedFile;
import com.streamarr.server.repositories.SkippedFileRepository;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortValue;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FakeSkippedFileRepository implements SkippedFileRepository {

  private final Map<Key, SkippedFile> database = new ConcurrentHashMap<>();
  private volatile RuntimeException saveFailure;

  /** Makes every later save throw {@code failure} and store nothing. */
  public void failSavesWith(RuntimeException failure) {
    saveFailure = failure;
  }

  @Override
  public void save(SkippedFile skippedFile) {
    if (saveFailure != null) {
      throw saveFailure;
    }

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

  @Override
  public List<Row<SkippedFile>> rowsBeyond(UUID libraryId, Seek seek) {
    var rows =
        database.values().stream()
            .filter(skippedFile -> skippedFile.libraryId().equals(libraryId))
            .map(
                skippedFile ->
                    new Row<>(
                        skippedFile,
                        new Position(
                            new SortValue.OfText(skippedFile.filepathUri()), skippedFile.id())))
            .toList();
    return InMemoryKeyset.rowsBeyond(rows, seek);
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
