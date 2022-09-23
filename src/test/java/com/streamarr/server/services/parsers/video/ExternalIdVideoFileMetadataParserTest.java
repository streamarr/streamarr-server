package com.streamarr.server.services.parsers.video;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("UnitTest")
@DisplayName("External Id Video File Metadata Parsing Tests")
public class ExternalIdVideoFileMetadataParserTest {

    private final ExternalIdVideoFileMetadataParser externalIdVideoFileMetadataParser = new ExternalIdVideoFileMetadataParser();

    @Nested
    @DisplayName("Should successfully extract both id and source from filename")
    public class SuccessfulExternalIdAndSourceExtractionTests {

        record TestCase(ExternalVideoSourceType source, String id, String filename) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase(ExternalVideoSourceType.IMDB, "tt13327038", "Do Revenge (2022) [imdb-tt13327038][WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.IMDB, "tt13327038", "Do Revenge (2022) [IMDB-tt13327038][WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.IMDB, "tt13327038", "Do Revenge (2022) [imdb tt13327038][WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.IMDB, "tt13327038", "Do Revenge (2022) [IMDB tt13327038][WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.IMDB, "tt13327038", "Do Revenge (2022) {imdb-tt13327038}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.IMDB, "tt13327038", "Do Revenge (2022) {IMDB-tt13327038}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.IMDB, "tt13327038", "Do Revenge (2022) {imdb tt13327038}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.IMDB, "tt13327038", "Do Revenge (2022) {IMDB tt13327038}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.TMDB, "762504", "Nope (2022) [tmdb-762504][WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.TMDB, "762504", "Nope (2022) [tmdb-762504][WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.TMDB, "762504", "Nope (2022) [tmdb 762504][WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.TMDB, "762504", "Nope (2022) [tmdb 762504][WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.TMDB, "762504", "Nope (2022) {tmdb-762504}[WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.TMDB, "762504", "Nope (2022) {tmdb-762504}[WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.TMDB, "762504", "Nope (2022) {tmdb 762504}[WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
                new TestCase(ExternalVideoSourceType.TMDB, "762504", "Nope (2022) {tmdb 762504}[WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv")
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = externalIdVideoFileMetadataParser.parse(testCase.filename());

                    assertThat(result.orElseThrow().externalSource()).isEqualTo(testCase.source());
                    assertThat(result.orElseThrow().externalId()).isEqualTo(testCase.id());
                })
            );
        }
    }

    @Nested
    @DisplayName("Should fail to extract both id and source from filename")
    public class ShouldFailToExtractIdAndSource {

        record TestCase(String testName, String filename) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given title without external ID tag", "Do Revenge (2022) imdb tt13327038 [WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
                new TestCase("when given null input", null),
                new TestCase("when given empty input", ""),
                new TestCase("when given blank input", " ")
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.testName(),
                () -> {
                    var result = externalIdVideoFileMetadataParser.parse(testCase.filename());

                    assertThat(result).isEmpty();
                })
            );
        }
    }

}
