package com.streamarr.server.services.extraction.show;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("UnitTest")
@DisplayName("Season Filename Extraction Tests")
public class SeasonPathExtractionServiceTest {

    private final SeasonPathExtractionService seasonPathExtractionService = new SeasonPathExtractionService();

    @Nested
    @DisplayName("Should successfully extract series number")
    public class SuccessfulExtractionTests {

        record TestCase(String name, String filename, int seasonNumber, boolean isSeasonDirectory) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(

                new TestCase("1", "/Drive/Season 1", 1, true),
                new TestCase("1", "/Drive/Season 2", 2, true),
                new TestCase("1", "/Drive/Season 02", 2, true),
                new TestCase("1", "/Drive/Seinfeld/S02", 2, true),
                new TestCase("1", "/Drive/Seinfeld/2", 2, true),
                new TestCase("1", "/Drive/Season 2009", 2009, true),
                new TestCase("1", "/Drive/Season1", 1, true),
                new TestCase("1", "The Wonder Years/The.Wonder.Years.S04.PDTV.x264-JCH", 4, true),
                new TestCase("1", "/Drive/Season 7 (2016)", 7, false),
                new TestCase("1", "/Drive/Staffel 7 (2016)", 7, false),
                new TestCase("1", "/Drive/Stagione 7 (2016)", 7, false),
                new TestCase("1", "/Drive/3.Staffel", 3, false),
                new TestCase("1", "/Drive/extras", 0, true),
                new TestCase("1", "/Drive/specials", 0, true)

            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = seasonPathExtractionService.extract(testCase.filename()).orElseThrow();

                    assertThat(result.seasonNumber().orElseThrow()).isEqualTo(testCase.seasonNumber());
                    assertThat(result.isSeasonFolder()).isEqualTo(testCase.isSeasonDirectory());
                })
            );
        }
    }

    @Nested
    @DisplayName("Should fail to extract series number")
    public class UnsuccessfulExtractionTests {

        record TestCase(String name, String filename, boolean isSeasonDirectory) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(

                new TestCase("1", "/Drive/Season (8)", false),
                new TestCase("1", "/Drive/s06e05", false),
                new TestCase("1", "/Drive/The.Legend.of.Condor.Heroes.2017.V2.web-dl.1080p.h264.aac-hdctv", false)

            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = seasonPathExtractionService.extract(testCase.filename()).orElseThrow();

                    assertThat(result.seasonNumber().isEmpty()).isTrue();
                    assertThat(result.isSeasonFolder()).isEqualTo(testCase.isSeasonDirectory());
                })
            );
        }
    }
}
