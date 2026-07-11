package com.streamarr.server.services.parsers.show;

import com.streamarr.server.services.parsers.MetadataParser;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class SeriesPathMetadataParser implements MetadataParser<String> {

  public Optional<String> parse(String path) {
    return Optional.of("");
  }
}
