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

  private final Map<Key, Stored> database = new ConcurrentHashMap<>();

  @Override
  public void save(SkippedFile skippedFile) {
    database.compute(
        new Key(skippedFile.libraryId(), skippedFile.filepathUri()),
        (_, stored) -> {
          var id = stored == null ? UUID.randomUUID() : stored.id();
          return new Stored(id, skippedFile);
        });
  }

  @Override
  public void deleteSkippedFilesOfOtherLibraryOperations(UUID libraryId, UUID libraryOperationId) {
    database
        .values()
        .removeIf(
            stored ->
                stored.skippedFile().libraryId().equals(libraryId)
                    && !stored.skippedFile().libraryOperationId().equals(libraryOperationId));
  }

  @Override
  public List<Row<SkippedFile>> rowsBeyond(UUID libraryId, Seek seek) {
    var rows =
        database.values().stream()
            .filter(stored -> stored.skippedFile().libraryId().equals(libraryId))
            .map(
                stored ->
                    new Row<>(
                        stored.skippedFile(),
                        new Position(
                            new SortValue.OfText(stored.skippedFile().filepathUri()), stored.id())))
            .toList();
    return InMemoryKeyset.rowsBeyond(rows, seek);
  }

  /** The library's skipped files in filepath order. */
  public List<SkippedFile> skippedFilesOf(UUID libraryId) {
    return database.values().stream()
        .map(Stored::skippedFile)
        .filter(skippedFile -> skippedFile.libraryId().equals(libraryId))
        .sorted(Comparator.comparing(SkippedFile::filepathUri))
        .toList();
  }

  private record Key(UUID libraryId, String filepathUri) {}

  private record Stored(UUID id, SkippedFile skippedFile) {}
}
