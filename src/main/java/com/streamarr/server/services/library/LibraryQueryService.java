package com.streamarr.server.services.library;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
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

  public MediaPage<? extends BaseCollectable<?>> getItems(
      AuthenticatedIdentity identity, MediaType kind, MediaPaginationOptions options) {
    authorization.requireProfile(identity);
    return switch (kind) {
      case MOVIE -> movies.getMoviesWithFilter(options);
      case SERIES -> series.getSeriesWithFilter(options);
      default -> throw new UnsupportedMediaTypeException(kind.name());
    };
  }
}
