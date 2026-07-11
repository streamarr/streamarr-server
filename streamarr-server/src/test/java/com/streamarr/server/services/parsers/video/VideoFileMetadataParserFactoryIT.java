package com.streamarr.server.services.parsers.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("IntegrationTest")
@DisplayName("Video File Metadata Parser Factory Integration Tests")
@SpringBootTest(
    classes = {
      VideoFileMetadataParserFactory.class,
      DefaultVideoFileMetadataParser.class,
      ExternalIdVideoFileMetadataParser.class
    })
class VideoFileMetadataParserFactoryIT {

  @Autowired private VideoFileMetadataParserFactory videoFileMetadataParserFactory;

  @Test
  @DisplayName("Should parse video's external id when given filename containing an id tag")
  void shouldParseExternalId() {
    var result =
        videoFileMetadataParserFactory.parseMetadata(
            "Nope (2022) [tmdb-762504][WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv");

    assertThat(result).isPresent();
    assertThat(result.get().externalId()).isEqualTo("762504");
    assertThat(result.get().externalSource()).isEqualTo(ExternalSourceType.TMDB);
  }

  @Test
  @DisplayName("Should parse video's name and year when given filename without an id tag")
  void shouldParseNameAndYear() {
    var result = videoFileMetadataParserFactory.parseMetadata("Spider Man (2002)");

    assertThat(result).isPresent();
    assertThat(result.get().title()).isEqualTo("Spider Man");
    assertThat(result.get().year()).isEqualTo("2002");
  }
}
