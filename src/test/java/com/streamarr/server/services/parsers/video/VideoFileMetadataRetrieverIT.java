package com.streamarr.server.services.parsers.video;

import com.streamarr.server.services.parsers.MetadataParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("IntegrationTest")
@DisplayName("Video File Metadata Parser Injection Retrieval Integration Tests")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(loader = AnnotationConfigContextLoader.class)
public class VideoFileMetadataRetrieverIT {

    @Configuration
    @ComponentScan(basePackages = {"com.streamarr.server.services.parsers"})
    static class ContextConfiguration {
    }

    @Autowired
    private List<MetadataParser<VideoFileMetadata>> parsers;

    @Test
    @DisplayName("Should inject only video file parsers and not episode file parsers")
    void shouldInjectCorrectServices() {
        assertThat(parsers.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should inject parsers in the correct order based on definition in services.")
    void shouldInjectServicesInOrder() {
        assertThat(parsers.get(0)).isInstanceOf(ExternalIdVideoFileMetadataParser.class);
        assertThat(parsers.get(1)).isInstanceOf(DefaultVideoFileMetadataParser.class);
    }
}
