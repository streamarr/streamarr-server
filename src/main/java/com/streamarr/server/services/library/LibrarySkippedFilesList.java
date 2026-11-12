package com.streamarr.server.services.library;

import com.streamarr.server.domain.SkippedFile;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.repositories.SkippedFileRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The files that the latest scan of a library skipped, in filepath order. */
@Component
@RequiredArgsConstructor
public class LibrarySkippedFilesList implements PagedList<SkippedFile, UUID> {

  private static final ListOrder BY_FILEPATH =
      ListOrder.builder().valueKind(SortValueKind.TEXT).direction(SortDirection.ASC).build();

  private final SkippedFileRepository skippedFileRepository;

  @Override
  public ListOrder order(UUID libraryId) {
    return BY_FILEPATH;
  }

  @Override
  public List<Row<SkippedFile>> rowsBeyond(UUID libraryId, Seek seek) {
    return skippedFileRepository.rowsBeyond(libraryId, seek);
  }
}
