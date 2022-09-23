package com.streamarr.server.services.parsers.video;

import com.streamarr.server.services.parsers.MetadataParser;
import io.micrometer.core.instrument.util.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

@Service
@Order(50)
public class ExternalIdVideoFileMetadataParser implements MetadataParser<VideoFileMetadata> {

    // TODO: Should this also work on things like /tmdb-19996/avatar.mkv?
    private final static Pattern SOURCE_ID_REGEX = Pattern.compile(".*(?<=[\\[\\{](?i)(?<source>imdb|tmdb)[ \\-])(?<id>.+?)(?=[\\]\\}]).*");

    @Override
    public Optional<VideoFileMetadata> parse(String filename) {

        if (StringUtils.isBlank(filename)) {
            return Optional.empty();
        }

        var matcher = SOURCE_ID_REGEX.matcher(filename);

        if (!matcher.matches()) {
            return Optional.empty();
        }

        return Optional.of(VideoFileMetadata.builder()
            .externalId(matcher.group("id"))
            .externalSource(ExternalVideoSourceType.valueOf(matcher.group("source").toUpperCase()))
            .build());
    }
}
