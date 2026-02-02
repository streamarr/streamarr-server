package com.streamarr.server.services.parsers.video;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.services.parsers.MetadataParser;
import io.micrometer.core.instrument.util.StringUtils;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service
@Order(50)
public class ExternalIdVideoFileMetadataParser implements MetadataParser<VideoFileParserResult> {

  private static final Pattern SOURCE_ID_REGEX =
      Pattern.compile(".*(?<=[\\[\\{](?i)(?<source>imdb|tmdb)[ \\-])(?<id>.+?)(?=[\\]\\}]).*");

  @Override
  public Optional<VideoFileParserResult> parse(String filename) {

    if (StringUtils.isBlank(filename)) {
      return Optional.empty();
    }

    var matcher = SOURCE_ID_REGEX.matcher(filename);

    if (!matcher.matches()) {
      return Optional.empty();
    }

    return Optional.of(
        VideoFileParserResult.builder()
            .externalId(matcher.group("id"))
            .externalSource(ExternalSourceType.valueOf(matcher.group("source").toUpperCase()))
            .build());
  }
}
