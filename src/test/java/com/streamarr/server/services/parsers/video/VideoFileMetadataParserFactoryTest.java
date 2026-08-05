package com.streamarr.server.services.parsers.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.services.parsers.MetadataParser;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Video File Metadata Parser Factory Tests")
class VideoFileMetadataParserFactoryTest {

  @Test
  @DisplayName("Should return parser result when parser recognizes filename")
  void shouldReturnParserResultWhenParserRecognizesFilename() {
    var expected = VideoFileParserResult.builder().title("Garnet Vale").year("2002").build();
    MetadataParser<VideoFileParserResult> recognizingParser = _ -> Optional.of(expected);
    var factory = new VideoFileMetadataParserFactory(List.of(recognizingParser));

    var result = factory.parseMetadata("Garnet Vale (2002).mkv");

    assertThat(result).contains(expected);
  }

  @Test
  @DisplayName("Should parse external ID when filename contains an ID tag")
  void shouldParseExternalIdWhenFilenameContainsIdTag() {
    var factory = parserFactoryWithProductionParsers();

    var result =
        factory
            .parseMetadata("Glint (2022) [tmdb-815339][WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv")
            .orElseThrow();

    assertThat(result.externalId()).isEqualTo("815339");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.TMDB);
  }

  @Test
  @DisplayName("Should parse title and year when filename has no ID tag")
  void shouldParseTitleAndYearWhenFilenameHasNoIdTag() {
    var factory = parserFactoryWithProductionParsers();

    var result = factory.parseMetadata("Garnet Vale (2002)").orElseThrow();

    assertThat(result.title()).isEqualTo("Garnet Vale");
    assertThat(result.year()).isEqualTo("2002");
  }

  private VideoFileMetadataParserFactory parserFactoryWithProductionParsers() {
    return new VideoFileMetadataParserFactory(
        List.of(new ExternalIdVideoFileMetadataParser(), new DefaultVideoFileMetadataParser()));
  }
}
