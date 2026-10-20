package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.domain.metadata.Review;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.RatingRepository;
import com.streamarr.server.repositories.ReviewRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.events.library.LibraryItemAddedEvent;
import com.streamarr.server.services.events.library.LibraryItemRemovedEvent;
import com.streamarr.server.services.events.library.LibraryItemUpdatedEvent;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.pagination.LetterJumpResolver;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MovieService {

  private final MovieRepository movieRepository;
  private final CreditService creditService;
  private final PaginationService paginationService;
  private final ArtworkRequestService artworkRequestService;
  private final ImageService imageService;
  private final MediaFileRepository mediaFileRepository;
  private final PersonRepository personRepository;
  private final GenreRepository genreRepository;
  private final CompanyRepository companyRepository;
  private final RatingRepository ratingRepository;
  private final ReviewRepository reviewRepository;
  private final WatchHistoryRepository watchHistoryRepository;
  private final ApplicationEventPublisher events;

  /**
   * Attaches the file to the movie with this TMDB id in the file's own library. Versions of a title
   * in one library share its movie. A movie in another library never takes the file.
   */
  @Transactional
  public Optional<Movie> addMediaFileToMovieByTmdbId(String id, MediaFile mediaFile) {
    var movie = movieRepository.findInLibraryByTmdbId(mediaFile.getLibraryId(), id);

    if (movie.isEmpty()) {
      log.debug("No movie found with TMDB ID: {}", id);
      return Optional.empty();
    }

    movie.get().addFile(mediaFile);
    var saved = movieRepository.saveAndFlush(movie.get());
    events.publishEvent(LibraryItemUpdatedEvent.forEveryProfile(saved, MediaType.MOVIE));
    return Optional.of(saved);
  }

  @Transactional
  public boolean deleteMovieById(UUID movieId) {
    return movieRepository
        .findById(movieId)
        .map(
            movie -> {
              deleteMovies(List.of(movie));
              events.publishEvent(LibraryItemRemovedEvent.of(movie, MediaType.MOVIE));
              return true;
            })
        .orElse(false);
  }

  @Transactional
  public void deleteByLibraryId(UUID libraryId) {
    deleteMovies(movieRepository.findByLibrary_Id(libraryId));
  }

  private void deleteMovies(List<Movie> movies) {
    if (movies.isEmpty()) {
      return;
    }

    imageService.prepareMovieDeletion(movies.stream().map(Movie::getId).toList());
    movieRepository.deleteAll(movies);
  }

  @Transactional
  public Movie saveMovieWithMediaFile(Movie movie, MediaFile mediaFile) {
    var savedMovie = movieRepository.saveAndFlush(movie);

    savedMovie.addFile(mediaFile);

    return movieRepository.save(savedMovie);
  }

  @Transactional
  public Movie createMovieWithAssociations(
      MetadataResult<Movie> metadataResult, MediaFile mediaFile, ArtworkRun artworkRun) {
    var movie = metadataResult.entity();
    creditService
        .getOrCreateCredits(
            MediaTitleCreditsRequest.fromMetadataResult(metadataResult, ImageRefreshMode.PRESERVE))
        .copyTo(movie);

    var savedMovie = saveMovieWithMediaFile(movie, mediaFile);

    requestPrimaryArtwork(savedMovie.getId(), metadataResult.imageSources(), artworkRun);

    events.publishEvent(LibraryItemAddedEvent.of(savedMovie, MediaType.MOVIE));

    return savedMovie;
  }

  @Transactional
  public Movie refreshMovieMetadata(
      Movie existing, MetadataResult<Movie> metadataResult, ArtworkRun artworkRun) {
    var fresh = metadataResult.entity();

    existing.setTitle(fresh.getTitle());
    existing.setOriginalTitle(fresh.getOriginalTitle());
    existing.setTitleSort(fresh.getTitleSort());
    existing.setTagline(fresh.getTagline());
    existing.setSummary(fresh.getSummary());
    existing.setRuntime(fresh.getRuntime());
    existing.setContentRating(fresh.getContentRating());
    existing.setReleaseDate(fresh.getReleaseDate());
    creditService
        .getOrCreateCredits(
            MediaTitleCreditsRequest.fromMetadataResult(
                metadataResult, artworkRun.imageRefreshMode()))
        .copyTo(existing);

    var saved = movieRepository.saveAndFlush(existing);
    requestPrimaryArtwork(saved.getId(), metadataResult.imageSources(), artworkRun);
    events.publishEvent(LibraryItemUpdatedEvent.forEveryProfile(saved, MediaType.MOVIE));
    return saved;
  }

  private void requestPrimaryArtwork(
      UUID movieId, List<ImageSource> sources, ArtworkRun artworkRun) {
    artworkRequestService.requestPrimaryArtwork(
        artworkRun,
        ArtworkSources.builder()
            .targetId(movieId)
            .targetType(ImageEntityType.MOVIE)
            .sources(sources)
            .build());
  }

  @Transactional(readOnly = true)
  public Optional<Movie> findById(UUID id) {
    return movieRepository.findById(id);
  }

  /** Returns the movies with the given ids; an id without a movie is left out. */
  @Transactional(readOnly = true)
  public List<Movie> findMoviesByIds(Collection<UUID> movieIds) {
    return movieRepository.findAllById(movieIds);
  }

  /** Returns the given movie ids whose movie has no provider id. */
  @Transactional(readOnly = true)
  public Set<UUID> findUnmatchedMovieIds(Collection<UUID> movieIds) {
    return movieRepository.findUnmatchedMovieIds(movieIds);
  }

  @Transactional(readOnly = true)
  public List<MediaFile> findMediaFiles(UUID movieId) {
    return mediaFileRepository.findByMediaId(movieId);
  }

  @Transactional(readOnly = true)
  public List<Company> findStudios(UUID movieId) {
    return companyRepository.findByMovieId(movieId);
  }

  @Transactional(readOnly = true)
  public List<Person> findCast(UUID movieId) {
    return personRepository.findCastByMovieId(movieId);
  }

  @Transactional(readOnly = true)
  public List<Person> findDirectors(UUID movieId) {
    return personRepository.findDirectorsByMovieId(movieId);
  }

  @Transactional(readOnly = true)
  public List<Genre> findGenres(UUID movieId) {
    return genreRepository.findByMovieId(movieId);
  }

  @Transactional(readOnly = true)
  public List<Rating> findRatings(UUID movieId) {
    return ratingRepository.findByMovie_Id(movieId);
  }

  @Transactional(readOnly = true)
  public List<Review> findReviews(UUID movieId) {
    return reviewRepository.findByMovie_Id(movieId);
  }

  public MediaPage<Movie> getMoviesWithFilter(MediaPaginationOptions options) {
    var resolvedOptions =
        LetterJumpResolver.resolve(options, movieRepository::findLetterJumpPredecessor);

    var movies =
        resolvedOptions.getCursorId().isPresent()
            ? movieRepository.seekWithFilter(resolvedOptions)
            : movieRepository.findFirstWithFilter(resolvedOptions);

    var lastWatchedByMovieId = lastWatchedFor(resolvedOptions.getMediaFilter(), movies);

    var pageItems =
        movies.stream()
            .map(
                movie ->
                    new PageItem<>(
                        movie,
                        getOrderByValue(
                            resolvedOptions.getMediaFilter(), movie, lastWatchedByMovieId)))
            .toList();

    return paginationService.buildMediaPage(
        pageItems, resolvedOptions, movieRepository::seekWithFilter);
  }

  private Map<UUID, Instant> lastWatchedFor(MediaFilter filter, List<Movie> movies) {
    if (filter.getSortBy() != OrderMediaBy.LAST_WATCHED || movies.isEmpty()) {
      return Map.of();
    }
    return watchHistoryRepository.findLastWatchedByItemIds(
        filter.getProfileId(),
        CollectableScope.MOVIE_OR_EPISODE,
        movies.stream().map(Movie::getId).toList());
  }

  private Object getOrderByValue(
      MediaFilter filter, Movie movie, Map<UUID, Instant> lastWatchedByMovieId) {
    return switch (filter.getSortBy()) {
      case TITLE -> movie.getTitleSort();
      case ADDED -> movie.getCreatedOn();
      case RELEASE_DATE -> movie.getReleaseDate();
      case RUNTIME -> movie.getRuntime();
      case LAST_WATCHED -> lastWatchedByMovieId.get(movie.getId());
    };
  }
}
