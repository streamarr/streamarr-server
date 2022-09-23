package com.streamarr.server.services.parsers.video;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Tag("IntegrationTest")
@DisplayName("Video File Metadata Parser Factory Integration Tests")
@SpringBootTest(classes = {VideoFileMetadataParserFactory.class, DefaultVideoFileMetadataParser.class, ExternalIdVideoFileMetadataParser.class})
public class VideoFileMetadataParserFactoryIT {

    @SpyBean
    private ExternalIdVideoFileMetadataParser externalIdVideoFileMetadataParserSpy;

    @SpyBean
    private DefaultVideoFileMetadataParser defaultVideoFileMetadataParserSpy;

    @Autowired
    private VideoFileMetadataParserFactory videoFileMetadataParserFactory;

    @Test
    @DisplayName("Should parse video's external id when given filename containing an id tag")
    void shouldParseExternalId() {
        var result = videoFileMetadataParserFactory.parseMetadata("Nope (2022) [tmdb-762504][WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv");

        assertThat(result).isPresent();
        assertThat(result.get().externalId()).isEqualTo("762504");
        assertThat(result.get().externalSource()).isEqualTo(ExternalVideoSourceType.TMDB);
    }

    @Test
    @DisplayName("Should only attempt to parse video's external id when given filename and not attempt to parse title")
    void shouldParseExternalIdAndNotTitle() {
        videoFileMetadataParserFactory.parseMetadata("Nope (2022) [tmdb-762504][WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv");

        verify(externalIdVideoFileMetadataParserSpy, times(1)).parse(anyString());
        verify(defaultVideoFileMetadataParserSpy, never()).parse(anyString());
    }

    @Test
    @DisplayName("Should parse video's name and year when given filename without an id tag")
    void shouldParseNameAndYear() {
        var result = videoFileMetadataParserFactory.parseMetadata("Spider Man (2002)");

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("Spider Man");
        assertThat(result.get().year()).isEqualTo("2002");
    }

    @Test
    @DisplayName("Should attempt to parse video's external id and name and year when given filename containing only title and no tags")
    void shouldAttemptExternalIdAndNameAndYearParsing() {
        videoFileMetadataParserFactory.parseMetadata("Spider Man (2002)");

        verify(externalIdVideoFileMetadataParserSpy, times(1)).parse(anyString());
        verify(defaultVideoFileMetadataParserSpy, times(1)).parse(anyString());
    }
}
