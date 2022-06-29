package com.streamarr.server.services.extraction;

import java.util.Optional;

public interface MediaExtractor<T> {
    Optional<T> extract(String filename);

}
