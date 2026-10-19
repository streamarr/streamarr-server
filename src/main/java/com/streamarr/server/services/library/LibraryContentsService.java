package com.streamarr.server.services.library;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.events.library.LibraryItemChangedEvent;
import com.streamarr.server.services.pagination.ItemSortValue;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.PageItem;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
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

  @Transactional(readOnly = true)
  public Optional<PageItem<BaseCollectable<?>>> findMatchingItem(
      LibraryItemChangedEvent change, MediaFilter filter) {
    return findSortValue(change, filter)
        .flatMap(value -> findItem(change).map(item -> new PageItem<>(item, value.value())));
  }

  private Optional<ItemSortValue> findSortValue(
      LibraryItemChangedEvent change, MediaFilter filter) {
    return switch (change.kind()) {
      case MOVIE -> movies.findItemPosition(change.itemId(), filter);
      case SERIES -> series.findItemPosition(change.itemId(), filter);
      default -> throw new IllegalArgumentException("Library items must be movies or series");
    };
  }

  @Transactional(readOnly = true)
  public Optional<BaseCollectable<?>> findItem(LibraryItemChangedEvent change) {
    return switch (change.kind()) {
      case MOVIE -> movies.findById(change.itemId()).map(item -> item);
      case SERIES -> series.findById(change.itemId()).map(item -> item);
      default -> throw new IllegalArgumentException("Library items must be movies or series");
    };
  }
}
