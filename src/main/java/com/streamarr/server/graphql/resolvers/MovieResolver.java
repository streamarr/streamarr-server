package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.MovieRepository;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.UUID;

@DgsComponent
@RequiredArgsConstructor
public class MovieResolver {

    private final MovieRepository movieRepository;

    @DgsQuery
    public Optional<Movie> movie(String id) {

        return movieRepository.findById(UUID.fromString(id));
    }
}
