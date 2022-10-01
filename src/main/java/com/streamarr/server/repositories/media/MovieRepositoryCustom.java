package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;

import java.util.List;

public interface MovieRepositoryCustom {

    List<Movie> seekWithFilter(MediaPaginationOptions options);

    List<Movie> findFirstWithFilter(MediaPaginationOptions options);
}
