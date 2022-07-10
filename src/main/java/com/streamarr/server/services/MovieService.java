package com.streamarr.server.services;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.BaseEntity;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.PaginationOptions;
import com.streamarr.server.repositories.movie.MovieRepository;
import graphql.relay.Connection;
import graphql.relay.DefaultEdge;
import graphql.relay.Edge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MovieService {

    private final MovieRepository movieRepository;
    private final CursorUtil cursorUtil;
    private final RelayPaginationService relayPaginationService;

    public Connection<? extends BaseCollectable<?>> getMoviesWithFilter(
        int first,
        String after,
        int last,
        String before,
        MediaFilter filter) {

        if (filter == null) {
            filter = buildDefaultMovieFilter();
        }

        var options = relayPaginationService.getPaginationOptions(first, after, last, before);

        if (options.getCursor().isEmpty()) {
            return getFirstMoviesAsConnection(options, filter);
        }

        var mediaOptions = cursorUtil.decodeMediaCursor(options);

        return usingCursorGetMoviesAsConnection(mediaOptions);
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

    private List<Edge<? extends BaseEntity<?>>> mapItemsToEdges(List<Movie> movies, MediaPaginationOptions options) {
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
        // TODO: validate cursor. Shouldn't be able to use a stale cursor...

        var movies = movieRepository.seekWithFilter(options);
        var edges = mapItemsToEdges(movies, options);

        return relayPaginationService.buildConnection(edges, options.getPaginationOptions(), options.getCursorId());
    }
}
