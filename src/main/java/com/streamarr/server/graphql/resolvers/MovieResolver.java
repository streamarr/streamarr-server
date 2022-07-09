package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.MovieFile;
import com.streamarr.server.repositories.movie.MovieRepository;
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

    @DgsTypeResolver(name = "MediaFile")
    public String resolveMediaFile(MediaFile mediaFile) {
        if (mediaFile instanceof MovieFile) {
            return "MovieFile";
        } else {
            throw new RuntimeException("Invalid type: " + mediaFile.getClass().getName() + " found in MediaFileTypeResolver");
        }
    }
}
