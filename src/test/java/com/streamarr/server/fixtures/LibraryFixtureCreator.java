package com.streamarr.server.fixtures;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import java.util.UUID;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LibraryFixtureCreator {

  public Library buildFakeLibrary() {
    return Library.builder()
        .name("Test Library")
        .backend(LibraryBackend.LOCAL)
        .status(LibraryStatus.HEALTHY)
        .filepath("/library/" + UUID.randomUUID())
        .externalAgentStrategy(ExternalAgentStrategy.TMDB)
        .type(MediaType.MOVIE)
        .build();
  }

  public Library buildUnsavedLibrary(String name, String filepath) {
    return Library.builder()
        .name(name)
        .backend(LibraryBackend.LOCAL)
        .filepath(filepath)
        .externalAgentStrategy(ExternalAgentStrategy.TMDB)
        .type(MediaType.MOVIE)
        .build();
  }
}
