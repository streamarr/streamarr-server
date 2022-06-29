package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.LibraryRepository;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.UUID;

@DgsComponent
@RequiredArgsConstructor
public class LibraryResolver {

    private final LibraryRepository libraryRepository;

    @DgsQuery
    public Optional<Library> library(String id) {

        return libraryRepository.findById(UUID.fromString(id));
    }

    @DgsTypeResolver(name = "Media")
    public String resolveMedia(Object media) {
        if (media instanceof Movie) {
            return "Movie";
        } else {
            throw new RuntimeException("Invalid type: " + media.getClass().getName() + " found in MediaTypeResolver");
        }
    }
}
