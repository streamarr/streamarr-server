package com.streamarr.server.services.architecturefixture;

import reactor.core.publisher.Flux;

public class ReactiveServiceFixture {

  public Flux<String> libraryChanges() {
    return Flux.empty();
  }
}
