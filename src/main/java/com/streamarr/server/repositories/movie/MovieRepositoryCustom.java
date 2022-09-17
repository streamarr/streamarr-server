package com.streamarr.server.repositories.movie;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import io.vertx.core.Future;

import java.util.List;

public interface MovieRepositoryCustom {

    Future<Movie> saveAsync(Movie movie);

    List<Movie> seekWithFilter(MediaPaginationOptions options);

    List<Movie> findFirstWithFilter(MediaPaginationOptions options);
}
