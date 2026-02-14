package com.streamarr.server.services.parsers.video;

import static com.streamarr.server.services.parsers.ParserPatterns.EXTERNAL_ID_TAG;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.services.parsers.MetadataParser;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service
@Order(50)
public class ExternalIdVideoFileMetadataParser implements MetadataParser<VideoFileParserResult> {

  @Override
  public Optional<VideoFileParserResult> parse(String filename) {

    if (StringUtils.isBlank(filename)) {
      return Optional.empty();
    }

    var matcher = EXTERNAL_ID_TAG.matcher(filename);

    if (!matcher.find()) {
      return Optional.empty();
    }

    return Optional.of(
        VideoFileParserResult.builder()
            .externalId(matcher.group("id"))
            .externalSource(ExternalSourceType.valueOf(matcher.group("source").toUpperCase()))
            .build());
  }
}
