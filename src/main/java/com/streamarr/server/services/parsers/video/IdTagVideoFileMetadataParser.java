package com.streamarr.server.services.parsers.video;

import com.streamarr.server.services.parsers.MetadataParser;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class IdTagVideoFileMetadataParser implements MetadataParser<VideoFileMetadata> {

    // Extracts external identifier and source from filename
    private final static Pattern SOURCE_ID_REGEX = Pattern.compile("(?<=[\\[\\{](?i)(?<source>imdb|tmdb)[ \\-])(?<id>.+?)(?=[\\]\\}])");

    @Override
    public Optional<VideoFileMetadata> extract(String filename) {
        // TODO: Parse filename for source and id
        // TODO: Should this also work on things like /tmdb-19996/avatar.mkv?
        return Optional.empty();
    }
}
