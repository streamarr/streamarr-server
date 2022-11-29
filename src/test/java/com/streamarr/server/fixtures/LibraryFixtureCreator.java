package com.streamarr.server.fixtures;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LibraryFixtureCreator {

    public Library buildFakeLibrary() {
        return Library.builder()
            .name("Test Library")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepath("/library")
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build();
    }
}
