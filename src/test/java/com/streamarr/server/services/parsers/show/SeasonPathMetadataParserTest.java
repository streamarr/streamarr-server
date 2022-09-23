package com.streamarr.server.services.parsers.show;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("UnitTest")
@DisplayName("Season Path Extraction Tests")
public class SeasonPathMetadataParserTest {

    private final SeasonPathMetadataParser seasonPathMetadataParser = new SeasonPathMetadataParser();

    @Nested
    @DisplayName("Should successfully extract season number")
    public class SuccessfulExtractionTests {

        record TestCase(String filename, int seasonNumber, boolean isSeasonDirectory) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("/Drive/Season 1", 1, true),
                new TestCase("/Drive/Season 2", 2, true),
                new TestCase("/Drive/Season 02", 2, true),
                new TestCase("/Drive/Seinfeld/S02", 2, true),
                new TestCase("/Drive/Seinfeld/2", 2, true),
                new TestCase("/Drive/Season 2009", 2009, true),
                new TestCase("/Drive/Season1", 1, true),
                new TestCase("The Wonder Years/The.Wonder.Years.S04.PDTV.x264-JCH", 4, true),
                new TestCase("/Drive/Season 7 (2016)", 7, false),
                new TestCase("/Drive/Staffel 7 (2016)", 7, false),
                new TestCase("/Drive/Stagione 7 (2016)", 7, false),
                new TestCase("/Drive/3.Staffel", 3, false),
                new TestCase("/Drive/extras", 0, true),
                new TestCase("/Drive/specials", 0, true)

            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = seasonPathMetadataParser.parse(testCase.filename()).orElseThrow();

                    assertThat(result.seasonNumber().orElseThrow()).isEqualTo(testCase.seasonNumber());
                    assertThat(result.isSeasonFolder()).isEqualTo(testCase.isSeasonDirectory());
                })
            );
        }
    }

    @Nested
    @DisplayName("Should fail to extract season number")
    public class UnsuccessfulExtractionTests {

        record TestCase(String filename, boolean isSeasonDirectory) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("/Drive/Season (8)", false),
                new TestCase("/Drive/s06e05", false),
                new TestCase("/Drive/The.Legend.of.Condor.Heroes.2017.V2.web-dl.1080p.h264.aac-hdctv", false)

            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = seasonPathMetadataParser.parse(testCase.filename()).orElseThrow();

                    assertThat(result.seasonNumber()).isEmpty();
                    assertThat(result.isSeasonFolder()).isEqualTo(testCase.isSeasonDirectory());
                })
            );
        }
    }
}
