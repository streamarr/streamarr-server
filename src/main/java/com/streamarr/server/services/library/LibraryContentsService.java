package com.streamarr.server.services.library;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.events.library.LibraryItemChangedEvent;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.Row;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Builder
@RequiredArgsConstructor
public class LibraryContentsService {

  private final LibraryRepository libraries;
  private final MovieRepository movies;
  private final SeriesRepository series;
  private final AuthorizationService authorization;

  public UUID requireSubscriptionProfile(AuthenticatedIdentity identity) {
    return authorization.requireViewProfileActivity(identity);
  }

  public Optional<Library> findLibrary(UUID libraryId) {
    return libraries.findById(libraryId);
  }

  /** The changed item's row in the Library's list under the filter, when the filter admits it. */
  @Transactional(readOnly = true)
  public Optional<Row<BaseCollectable<?>>> findMatchingRow(
      LibraryItemChangedEvent change, MediaFilter filter) {
    var scope = filter.listScope();
    return switch (change.kind()) {
      case MOVIE -> movies.rowInLibrary(change.itemId(), scope).map(LibraryContentsService::row);
      case SERIES -> series.rowInLibrary(change.itemId(), scope).map(LibraryContentsService::row);
      default -> throw new UnsupportedMediaTypeException(change.kind().name());
    };
  }

  private static Row<BaseCollectable<?>> row(Row<? extends BaseCollectable<?>> row) {
    return new Row<>(row.item(), row.position());
  }

  @Transactional(readOnly = true)
  public Optional<BaseCollectable<?>> findItem(LibraryItemChangedEvent change) {
    return switch (change.kind()) {
      case MOVIE -> movies.findById(change.itemId()).map(item -> item);
      case SERIES -> series.findById(change.itemId()).map(item -> item);
      default -> throw new UnsupportedMediaTypeException(change.kind().name());
    };
  }
}
