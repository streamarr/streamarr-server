package com.streamarr.server.services;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.BaseEntity;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
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
            // Default filter if one isn't provided
            filter = MediaFilter.builder().build();
        }

        var options = relayPaginationService.getPaginationOptions(first, after, last, before);

        if (options.getCursor().isEmpty()) {
            var mediaOptions = MediaPaginationOptions.builder()
                .paginationOptions(options)
                .mediaFilter(filter)
                .build();

            var movies = movieRepository.findFirstWithFilter(mediaOptions);
            var edges = mapItemsToEdges(movies, mediaOptions);

            return relayPaginationService.buildConnection(edges, mediaOptions.getPaginationOptions(), mediaOptions.getCursorId());
        }

        // Extract id and sort values from cursor.
        var mediaOptions = cursorUtil.decodeMediaCursor(options);

        // TODO: validate cursor. Shouldn't be able to use a stale cursor...

        var movies = movieRepository.seekWithFilter(mediaOptions);
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
}
