package com.streamarr.server.services.extraction.video;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("UnitTest")
@DisplayName("Default Video File Extraction Tests")
public class DefaultVideoFileExtractionServiceTest {

    private final DefaultVideoFileExtractionService defaultVideoFileExtractionService = new DefaultVideoFileExtractionService();

    @Nested
    @DisplayName("Should successfully extract both title and year from filename")
    public class SuccessfulTitleAndYearExtractionTests {

        record TestCase(String title, String year, String filename) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("Spider Man", "2002", "Spider Man 2002"),
                new TestCase("Spider Man", "2002", "Spider Man (2002)"),
                new TestCase("Akira", "1988", "Akira 1988"),
                new TestCase("Akira", "1988", "Akira (1988)"),
                new TestCase("$", "1971", "$ 1971"),
                new TestCase("$", "1971", "$ (1971)"),
                new TestCase("Tricky Movie Name 2001", "2012", "Tricky Movie Name 2001 2012"),
                new TestCase("Tricky Movie Name 2001", "2012", "Tricky Movie Name 2001 (2012)"),
                new TestCase("Robin Hood", "2018", "Robin Hood [Multi-Subs] 2018"),
                new TestCase("Robin Hood", "2018", "Robin Hood [Multi-Subs] [2018]"),
                new TestCase("Robin Hood", "2018", "Robin Hood [Multi-Subs] (2018)"),
                new TestCase("A Goofy Movie", "1995", "[Multi-Subs] A Goofy Movie (1995)"),
                new TestCase("The.Unbearable.Weight.of.Massive.Talent", "2022", "The.Unbearable.Weight.of.Massive.Talent.2022.HDR.2160p.WEB.H265"),
                new TestCase("The Movie Title", "2010", "The Movie Title (2010) Ultimate Extended Edition [imdb-tt0066921][IMAX HYBRID][Bluray-1080p Proper][3D][DV HDR10][DTS 5.1][x264]"),
                new TestCase("Home Movie 2012-12-12", "2012", "Home Movie 2012-12-12 2012"),
                new TestCase("3 days to kill", "2014", "3 days to kill (2014)"),
                new TestCase("3.Days.to.Kill", "2014", "3.Days.to.Kill.2014.720p.BluRay.x264.YIFY"),
                new TestCase("Rain Man", "1988", "Rain Man 1988 REMASTERED 1080p BluRay x264 AAC - Ozlem"),
                new TestCase("A Movie", "1996", "A Movie (1996) - AnotherTitle 2019.mp4"),
                new TestCase("Maximum Ride", "2016", "Maximum Ride - 2016 - WEBDL-1080p - x264 AC3"),
                new TestCase("No Space", "2000", "No Space(2000)"),
                new TestCase("Mr. Rogers", "2019", "Mr. Rogers 2019"),
                new TestCase("300", "2006", "300 (2006)"),
                new TestCase("300 2", "2006", "300 2 (2006)"),
                new TestCase("300 - 2", "2006", "300 - 2 (2006)"),
                new TestCase("[REC]", "2007", "[REC] (2007) - [REMUX-1080p][AC3 5.1].mkv")
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = defaultVideoFileExtractionService.extract(testCase.filename());

                    assertThat(result.orElseThrow().title()).isEqualTo(testCase.title());
                    assertThat(result.orElseThrow().year()).isEqualTo(testCase.year());
                })
            );
        }
    }

    @Nested
    @DisplayName("Should successfully extract a title from filename")
    public class SuccessfulTitleExtractionTests {

        record TestCase(String title, String filename) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("a", " a "),
                new TestCase("$", " $ "),
                new TestCase("2002", " 2002 "),
                new TestCase("Just a Title", "Just a Title"),
                //new TestCase("Title", "(2012) Title"),
                new TestCase("Title With Future Year 3001", "Title With Future Year 3001"),
                new TestCase("Some Movie", "Some Movie 480p"),
                new TestCase("Some Movie", "Some Movie [480p]"),
                new TestCase("Home Movie 2012.12.12", "Home Movie 2012.12.12"),
                new TestCase("Home Movie 2012-12-12", "Home Movie 2012-12-12"),
                new TestCase("Known.Exclusion", "Known.Exclusion.4k"),
                new TestCase("Known.Exclusion", "Known.Exclusion.UltraHD"),
                new TestCase("Known.Exclusion", "Known.Exclusion.UHD"),
                new TestCase("Zuhdiyyat", "Zuhdiyyat"),
                new TestCase("Known.Exclusion", "Known.Exclusion.HDC"),
                new TestCase("Known.Exclusion", "Known.Exclusion.HDR"),
                new TestCase("Known.Exclusion", "Known.Exclusion.BDrip"),
                new TestCase("Known.Exclusion", "Known.Exclusion.BDrip-HDC"),
                new TestCase("Known.Exclusion", "Known.Exclusion.4K.UltraHD.HDR.BDrip-HDC")
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = defaultVideoFileExtractionService.extract(testCase.filename());

                    assertThat(result.orElseThrow().title()).isEqualTo(testCase.title());
                    assertThat(result.orElseThrow().year()).isNull();
                })
            );
        }
    }

    @Nested
    @DisplayName("Should return empty optional")
    public class UnsuccessfulExtractionTests {

        record TestCase(String name, String input) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given null input", null),
                new TestCase("when given empty input", ""),
                new TestCase("when given blank input", " ")
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = defaultVideoFileExtractionService.extract(testCase.input());

                    assertTrue(result.isEmpty());
                })
            );
        }
    }
}
