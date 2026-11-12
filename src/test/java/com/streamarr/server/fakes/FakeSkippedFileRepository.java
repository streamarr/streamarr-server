package com.streamarr.server.fakes;

import com.streamarr.server.domain.SkippedFile;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortValue;
import com.streamarr.server.repositories.SkippedFileRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class FakeSkippedFileRepository implements SkippedFileRepository {

  private final Map<Key, SkippedFile> database = new ConcurrentHashMap<>();
  private final Predicate<UUID> libraryOperationExists;
  private volatile RuntimeException saveFailure;
  private volatile Runnable beforeEachSave = () -> {};

  /** Writes for every library operation id. */
  public FakeSkippedFileRepository() {
    this(_ -> true);
  }

  /** Writes only for a library operation that {@code libraryOperationExists} still finds. */
  public FakeSkippedFileRepository(Predicate<UUID> libraryOperationExists) {
    this.libraryOperationExists = libraryOperationExists;
  }

  /** Runs {@code action} before every later save attempt. */
  public void runBeforeEachSave(Runnable action) {
    beforeEachSave = action;
  }

  /** Makes every later save throw {@code failure} and store nothing. */
  public void failSavesWith(RuntimeException failure) {
    saveFailure = failure;
  }

  @Override
  public boolean trySave(SkippedFile skippedFile) {
    beforeEachSave.run();
    if (saveFailure != null) {
      throw saveFailure;
    }

    if (!libraryOperationExists.test(skippedFile.libraryOperationId())) {
      return false;
    }

    database.put(new Key(skippedFile.libraryId(), skippedFile.filepathUri()), skippedFile);
    return true;
  }

  @Override
  public void deleteSkippedFilesOfOtherLibraryOperations(UUID libraryId, UUID libraryOperationId) {
    if (!libraryOperationExists.test(libraryOperationId)) {
      return;
    }

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
