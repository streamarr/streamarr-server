package com.streamarr.server.services.parsers.video;

import com.streamarr.server.services.parsers.MetadataParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Tag("UnitTest")
@DisplayName("Video File Metadata Parser Factory Tests")
@ExtendWith(MockitoExtension.class)
public class VideoFileMetadataParserFactoryTest {

    @Mock
    private DefaultVideoFileMetadataParser mockDefaultVideoFileMetadataParser;

    private final List<MetadataParser<VideoFileMetadata>> parsers = new ArrayList<>();

    private VideoFileMetadataParserFactory videoFileMetadataParserFactory;

    @BeforeEach
    void setup() {
        parsers.add(mockDefaultVideoFileMetadataParser);
        videoFileMetadataParserFactory = new VideoFileMetadataParserFactory(parsers);
    }

    @Test
    @DisplayName("Should parse and return result from MetadataParser when provided valid filename")
    void shouldSuccessfullyParseFilename() {
        var fakeResult = Optional.of(VideoFileMetadata.builder()
            .title("Spider Man")
            .year("2002")
            .build());

        when(mockDefaultVideoFileMetadataParser.parse(anyString())).thenReturn(fakeResult);

        var result = videoFileMetadataParserFactory.parseMetadata("Spider Man (2002).mkv");

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo(fakeResult.get().title());
        assertThat(result.get().year()).isEqualTo(fakeResult.get().year());
    }
}
