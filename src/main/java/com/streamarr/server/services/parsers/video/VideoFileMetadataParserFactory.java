package com.streamarr.server.services.parsers.video;

import com.streamarr.server.services.parsers.MetadataParser;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VideoFileMetadataParserFactory {

  private final List<MetadataParser<VideoFileParserResult>> parserList;

  public Optional<VideoFileParserResult> parseMetadata(String filename) {
    return parserList.stream()
        .map(parser -> parser.parse(filename))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .findFirst();
  }
}
