package com.streamarr.server.services.parsers;

import java.util.Optional;

public interface MetadataParser<T> {
    Optional<T> parse(String filename);

}
