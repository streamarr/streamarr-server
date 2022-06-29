package com.streamarr.server.services.extraction.show;

import com.streamarr.server.services.extraction.MediaExtractor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SeriesPathExtractionService implements MediaExtractor<String> {

    public Optional<String> extract(String path) {
        return Optional.of("");
    }
}
