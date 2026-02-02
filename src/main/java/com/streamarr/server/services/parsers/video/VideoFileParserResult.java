package com.streamarr.server.services.parsers.video;

import com.streamarr.server.domain.ExternalSourceType;
import lombok.Builder;

public record VideoFileParserResult(
    String title, String year, String externalId, ExternalSourceType externalSource) {
  @Builder
  public VideoFileParserResult {}
}
