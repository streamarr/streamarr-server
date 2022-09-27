package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.library.LibraryManagementService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.UUID;

@DgsComponent
@RequiredArgsConstructor
public class LibraryResolver {

    private final LibraryRepository libraryRepository;
    private final LibraryManagementService libraryManagementService;
    private final MovieService movieService;

    @DgsMutation
    public boolean refreshLibrary(String id) {
        libraryManagementService.refreshLibrary(UUID.fromString(id));
        return true;
    }

    @DgsQuery
    public Optional<Library> library(String id) {
        return libraryRepository.findById(UUID.fromString(id));
    }

    @DgsData(parentType = "Library")
    public Connection<? extends BaseCollectable<?>> items(@InputArgument MediaFilter filter, DataFetchingEnvironment dfe) {
        Library library = dfe.getSource();
        int first = dfe.getArgumentOrDefault("first", 0);
        String after = dfe.getArgument("after");
        int last = dfe.getArgumentOrDefault("last", 0);
        String before = dfe.getArgument("before");

        // TODO: where library.id == movie.libraryId

        // avoids multiple left joins for each type and ensures we only return single type.
        return switch (library.getType()) {
            case MOVIE -> movieService.getMoviesWithFilter(first, after, last, before, filter);
            default -> null;
        };
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
