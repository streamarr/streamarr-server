package com.streamarr.server.services.library;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.pagination.MediaFilter;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Builder
@RequiredArgsConstructor
public class LibraryQueryService {
  private final LibraryRepository libraries;
  private final MovieService movies;
  private final SeriesService series;
  private final AuthorizationService authorization;

  public List<Library> findAll(AuthenticatedIdentity identity) {
    authorization.requireProfile(identity);
    return libraries.findAll();
  }

  public Optional<Library> findById(AuthenticatedIdentity identity, UUID libraryId) {
    authorization.requireProfile(identity);
    return libraries.findById(libraryId);
  }

  /** Returns the libraries with the given ids; an id without a library is left out. */
  public List<Library> findByIds(Collection<UUID> libraryIds) {
    return libraries.findAllById(libraryIds);
  }

  /**
   * Reads the library's latest committed row in a new read-only transaction. The new transaction
   * suspends the caller's, so the caller's persistence context cannot supply its own copy of the
   * library. An after-commit listener still holds that context, and its copy can predate a status
   * that jOOQ wrote. The returned library is detached.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<Library> findLatestCommitted(UUID libraryId) {
    return libraries.findById(libraryId);
  }

  /**
   * Refuses a Library addition or removal subscription unless a Profile is selected and its live
   * authorization facts allow it to view its own activity.
   */
  public void requireSubscriptionProfile(AuthenticatedIdentity identity) {
    authorization.requireViewProfileActivity(identity);
  }

  /** A page of the Library's items of {@code kind}, in the filter's sort order. */
  public ListPage<BaseCollectable<?>> getItems(
      AuthenticatedIdentity identity, MediaType kind, MediaFilter filter, PageRequest request) {
    authorization.requireProfile(identity);
    return switch (kind) {
      case MOVIE -> movies.getMoviesWithFilter(filter, request).map(LibraryQueryService::item);
      case SERIES -> series.getSeriesWithFilter(filter, request).map(LibraryQueryService::item);
      default -> throw new UnsupportedMediaTypeException(kind.name());
    };
  }

  private static BaseCollectable<?> item(BaseCollectable<?> item) {
    return item;
  }
}
