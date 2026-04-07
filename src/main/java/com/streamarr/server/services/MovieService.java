package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.domain.metadata.Review;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.RatingRepository;
import com.streamarr.server.repositories.ReviewRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.List;
import java.util.Optional;
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
  private final PersonService personService;
  private final GenreService genreService;
  private final CompanyService companyService;
  private final PaginationService paginationService;
  private final ApplicationEventPublisher eventPublisher;
  private final ImageService imageService;
  private final MediaFileRepository mediaFileRepository;
  private final PersonRepository personRepository;
  private final GenreRepository genreRepository;
  private final CompanyRepository companyRepository;
  private final RatingRepository ratingRepository;
  private final ReviewRepository reviewRepository;

  @Transactional
  public Optional<Movie> addMediaFileToMovieByTmdbId(String id, MediaFile mediaFile) {
    var movie = movieRepository.findByTmdbId(id);

    if (movie.isEmpty()) {
      log.debug("No movie found with TMDB ID: {}", id);
      return Optional.empty();
    }

    movie.get().addFile(mediaFile);
    return Optional.of(movieRepository.saveAndFlush(movie.get()));
  }

  @Transactional
  public void deleteMovieById(UUID movieId) {
    imageService.deleteImagesForEntity(movieId, ImageEntityType.MOVIE);
    movieRepository.deleteById(movieId);
  }

  @Transactional
  public void deleteByLibraryId(UUID libraryId) {
    var movies = movieRepository.findByLibrary_Id(libraryId);
    if (movies.isEmpty()) {
      return;
    }

    for (var movie : movies) {
      imageService.deleteImagesForEntity(movie.getId(), ImageEntityType.MOVIE);
    }

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
      MetadataResult<Movie> metadataResult, MediaFile mediaFile) {
    var movie = metadataResult.entity();

    movie.setCast(
        personService.getOrCreatePersons(movie.getCast(), metadataResult.personImageSources()));
    movie.setDirectors(
        personService.getOrCreatePersons(
            movie.getDirectors(), metadataResult.personImageSources()));
    movie.setGenres(genreService.getOrCreateGenres(movie.getGenres()));
    movie.setStudios(
        companyService.getOrCreateCompanies(
            movie.getStudios(), metadataResult.companyImageSources()));

    var savedMovie = saveMovieWithMediaFile(movie, mediaFile);

    publishImageEvent(savedMovie.getId(), ImageEntityType.MOVIE, metadataResult.imageSources());

    return savedMovie;
  }

  @Transactional
  public Movie refreshMovieMetadata(Movie existing, MetadataResult<Movie> metadataResult) {
    var fresh = metadataResult.entity();

    existing.setTitle(fresh.getTitle());
    existing.setOriginalTitle(fresh.getOriginalTitle());
    existing.setTitleSort(fresh.getTitleSort());
    existing.setTagline(fresh.getTagline());
    existing.setSummary(fresh.getSummary());
    existing.setRuntime(fresh.getRuntime());
    existing.setContentRating(fresh.getContentRating());
    existing.setReleaseDate(fresh.getReleaseDate());

    existing.setCast(
        personService.getOrCreatePersons(fresh.getCast(), metadataResult.personImageSources()));
    existing.setDirectors(
        personService.getOrCreatePersons(
            fresh.getDirectors(), metadataResult.personImageSources()));
    existing.setGenres(genreService.getOrCreateGenres(fresh.getGenres()));
    existing.setStudios(
        companyService.getOrCreateCompanies(
            fresh.getStudios(), metadataResult.companyImageSources()));

    var saved = movieRepository.saveAndFlush(existing);
    publishImageEvent(saved.getId(), ImageEntityType.MOVIE, metadataResult.imageSources());
    return saved;
  }

  private void publishImageEvent(
      UUID entityId, ImageEntityType entityType, List<ImageSource> sources) {
    if (!sources.isEmpty()) {
      eventPublisher.publishEvent(new MetadataEnrichedEvent(entityId, entityType, sources));
    }
  }

  @Transactional(readOnly = true)
  public Optional<Movie> findById(UUID id) {
    return movieRepository.findById(id);
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
    var movies =
        options.getCursorId().isPresent()
            ? movieRepository.seekWithFilter(options)
            : movieRepository.findFirstWithFilter(options);

    var pageItems =
        movies.stream()
            .map(movie -> new PageItem<>(movie, getOrderByValue(options.getMediaFilter(), movie)))
            .toList();

    return paginationService.buildMediaPage(
        pageItems, options.getPaginationOptions(), options.getCursorId());
  }

  private Object getOrderByValue(MediaFilter filter, Movie movie) {
    return switch (filter.getSortBy()) {
      case TITLE -> movie.getTitleSort();
      case ADDED -> movie.getCreatedOn();
      case RELEASE_DATE -> movie.getReleaseDate();
      case RUNTIME -> movie.getRuntime();
      case LAST_WATCHED ->
          throw new UnsupportedOperationException("LAST_WATCHED not yet implemented");
    };
  }
}
