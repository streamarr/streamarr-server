package com.streamarr.server.services;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.mappers.PersonMappers;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.PaginationOptions;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import graphql.relay.Connection;
import graphql.relay.DefaultEdge;
import graphql.relay.Edge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MovieService {

    private final MovieRepository movieRepository;
    private final PersonMappers personMappers;
    private final PersonRepository personRepository;
    private final CursorUtil cursorUtil;
    private final RelayPaginationService relayPaginationService;

    @Transactional
    public Optional<Movie> addMediaFileToMovieByTmdbId(String id, MediaFile mediaFile) {
        var movie = movieRepository.findByTmdbId(id);

        // TODO: Finish this...
        if (movie.isEmpty()) {
            System.out.println("NO MOVIE FOUND WITH TMDB ID: " + id);
            return Optional.empty();
        }

        movie.get().addFile(mediaFile);
        return Optional.of(movieRepository.saveAndFlush(movie.get()));
    }

    @Transactional
    public Movie saveMovieWithMediaFileAndCast(Movie movie, MediaFile mediaFile, Set<Person> cast) {

        // TODO: Cleanup
        var testMovie = Movie.builder()
            .id(movie.getId())
            .createdBy(movie.getCreatedBy())
            .lastModifiedBy(movie.getLastModifiedBy())
            .title(movie.getTitle())
            .libraryId(movie.getLibraryId())
            .externalIds(movie.getExternalIds())
            .files(movie.getFiles())
            .tagline(movie.getTagline())
            .summary(movie.getSummary())
            .backdropPath(movie.getBackdropPath())
            .posterPath(movie.getPosterPath())
            .releaseDate(movie.getReleaseDate())
            .contentRating(movie.getContentRating())
            .studios(movie.getStudios())
            .build();

        var savedMovie = movieRepository.saveAndFlush(testMovie);

        savedMovie.setCast(cast);

        // TODO: Will this work?
        mediaFile.setStatus(MediaFileStatus.MATCHED);

        savedMovie.addFile(mediaFile);

        return movieRepository.save(savedMovie);
    }

    // TODO: Move to "PersonService"?
    public Set<Person> getOrCreateCast(Set<Person> cast) {
        return cast.stream().map(this::savePerson).collect(Collectors.toSet());
    }

    // TODO: Move to "PersonService"?
    @Transactional
    public Person savePerson(Person person) {
        var existingPerson = personRepository.findPersonBySourceId(person.getSourceId());

        if (existingPerson != null) {

            personMappers.updatePerson(person, existingPerson);

            return personRepository.save(existingPerson);
        }

        return personRepository.save(person);
    }

    public Connection<? extends BaseCollectable<?>> getMoviesWithFilter(
        int first,
        String after,
        int last,
        String before,
        MediaFilter filter) {

        if (filter == null) {
            filter = buildDefaultMovieFilter();
        }

        var paginationOptions = relayPaginationService.getPaginationOptions(first, after, last, before);

        if (paginationOptions.getCursor().isEmpty()) {
            return getFirstMoviesAsConnection(paginationOptions, filter);
        }

        var mediaOptionsFromCursor = cursorUtil.decodeMediaCursor(paginationOptions);

        validateDecodedCursorAgainstFilter(mediaOptionsFromCursor, filter);

        return usingCursorGetMoviesAsConnection(mediaOptionsFromCursor);
    }

    private MediaFilter buildDefaultMovieFilter() {
        return MediaFilter.builder().build();
    }

    private Connection<? extends BaseCollectable<?>> getFirstMoviesAsConnection(PaginationOptions options, MediaFilter filter) {
        var mediaOptions = MediaPaginationOptions.builder()
            .paginationOptions(options)
            .mediaFilter(filter)
            .build();

        var movies = movieRepository.findFirstWithFilter(mediaOptions);
        var edges = mapItemsToEdges(movies, mediaOptions);

        return relayPaginationService.buildConnection(edges, mediaOptions.getPaginationOptions(), mediaOptions.getCursorId());
    }

    private List<Edge<? extends BaseAuditableEntity<?>>> mapItemsToEdges(List<Movie> movies, MediaPaginationOptions options) {
        return movies.stream()
            .map(result -> {
                var orderByValue = getOrderByValue(options.getMediaFilter(), result);
                var newCursor = cursorUtil.encodeMediaCursor(options, result.getId(), orderByValue);

                return new DefaultEdge<>(result, newCursor);
            })
            .collect(Collectors.toList());
    }

    private Object getOrderByValue(MediaFilter filter, Movie movie) {
        return switch (filter.getSortBy()) {
            case TITLE -> movie.getTitle();
            case ADDED -> movie.getCreatedOn();
        };
    }

    private Connection<? extends BaseCollectable<?>> usingCursorGetMoviesAsConnection(MediaPaginationOptions options) {
        var movies = movieRepository.seekWithFilter(options);
        var edges = mapItemsToEdges(movies, options);

        return relayPaginationService.buildConnection(edges, options.getPaginationOptions(), options.getCursorId());
    }

    private void validateDecodedCursorAgainstFilter(MediaPaginationOptions decodedOptions, MediaFilter filter) {
        var previousFilter = decodedOptions.getMediaFilter();

        relayPaginationService.validateCursorField("sortBy", previousFilter.getSortBy(), filter.getSortBy());
        relayPaginationService.validateCursorField("sortDirection", previousFilter.getSortDirection(), filter.getSortDirection());
    }
}
