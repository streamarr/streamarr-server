package com.streamarr.server.utils;


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
@DisplayName("Video Filename Extractor Tests")
public class VideoFilenameExtractorTest {

    private final VideoFilenameExtractor videoFilenameExtractor = new VideoFilenameExtractor();

    @Nested
    @DisplayName("Should successfully extract both title and year from filename")
    public class SuccessfulTitleAndYearExtractionTests {

        record TestCase(String name, String title, String year, String filename) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given two word title with 20xx year", "Spider Man", "2002", "Spider Man 2002"),
                new TestCase("when given two word title with 20xx year surrounded by parenthesis", "Spider Man", "2002", "Spider Man (2002)"),
                new TestCase("when given one word title with 19xx year", "Akira", "1988", "Akira 1988"),
                new TestCase("when given one word title with 19xx year surrounded by parenthesis", "Akira", "1988", "Akira (1988)"),
                new TestCase("when given one character title with year", "$", "1971", "$ 1971"),
                new TestCase("when given one character title with year surrounded by parenthesis", "$", "1971", "$ (1971)"),
                new TestCase("when given year in title with year", "Tricky Movie Name 2001", "2012", "Tricky Movie Name 2001 2012"),
                new TestCase("when given year in title with year surrounded by parenthesis", "Tricky Movie Name 2001", "2012", "Tricky Movie Name 2001 (2012)"),
                new TestCase("when given title with flag before year", "Robin Hood", "2018", "Robin Hood [Multi-Subs] 2018"),
                new TestCase("when given title with flag before year surrounded by brackets", "Robin Hood", "2018", "Robin Hood [Multi-Subs] [2018]"),
                new TestCase("when given title with flag before year surrounded by parenthesis", "Robin Hood", "2018", "Robin Hood [Multi-Subs] (2018)"),
                new TestCase("when given flag in front of title with year", "A Goofy Movie", "1995", "[Multi-Subs] A Goofy Movie (1995)"),
                new TestCase("when given period separated filename", "The.Unbearable.Weight.of.Massive.Talent", "2022", "The.Unbearable.Weight.of.Massive.Talent.2022.HDR.2160p.WEB.H265"),
                new TestCase("when given trash guide's example filename", "The Movie Title", "2010", "The Movie Title (2010) Ultimate Extended Edition [imdb-tt0066921][IMAX HYBRID][Bluray-1080p Proper][3D][DV HDR10][DTS 5.1][x264]"),
                new TestCase("when given title containing date separated by dashes with 20xx year", "Home Movie 2012-12-12", "2012", "Home Movie 2012-12-12 2012"),
                new TestCase("when given title starting with digit", "3 days to kill", "2014", "3 days to kill (2014)"),
                new TestCase("when given title starting with digit and containing tags", "3.Days.to.Kill", "2014", "3.Days.to.Kill.2014.720p.BluRay.x264.YIFY"),
                new TestCase("when given title with tags outside of brackets", "Rain Man", "1988", "Rain Man 1988 REMASTERED 1080p BluRay x264 AAC - Ozlem"),
                new TestCase("when given two titles back to back", "A Movie", "1996", "A Movie (1996) - AnotherTitle 2019.mp4"),
                new TestCase("when given title and year separated by dashes", "Maximum Ride", "2016", "Maximum Ride - 2016 - WEBDL-1080p - x264 AC3"),
                new TestCase("when given title without any space before year", "No Space", "2000", "No Space(2000)"),
                new TestCase("when given title containing period", "Mr. Rogers", "2019", "Mr. Rogers 2019"),
                new TestCase("when given number title with 20xx year surrounded by parenthesis", "300", "2006", "300 (2006)"),
                new TestCase("when given number title with sequel and 20xx year surrounded by parenthesis", "300 2", "2006", "300 2 (2006)"),
                new TestCase("when given number title with sequel separated by a dash and 20xx year surrounded by parenthesis", "300 - 2", "2006", "300 - 2 (2006)")
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = videoFilenameExtractor.extractAndClean(testCase.filename());

                    assertThat(result.orElseThrow().title()).isEqualTo(testCase.title());
                    assertThat(result.orElseThrow().year()).isEqualTo(testCase.year());
                })
            );
        }
    }

    @Nested
    @DisplayName("Should successfully extract a title from filename")
    public class SuccessfulTitleExtractionTests {

        record TestCase(String name, String title, String filename) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given single padded character", "a", " a "),
                new TestCase("when given single padded symbol", "$", " $ "),
                new TestCase("when given only 20xx year", "2002", " 2002 "),
                new TestCase("when given just a title", "Just a Title", "Just a Title"),
                new TestCase("when given a year before the title", "Title", "(2012) Title"),
                new TestCase("when given title with 3xxx year", "Title With Future Year 3001", "Title With Future Year 3001"),
                new TestCase("when given title with 480p exclusion", "Some Movie", "Some Movie 480p"),
                new TestCase("when given title with 480p exclusion inside tag", "Some Movie", "Some Movie [480p]"),
                new TestCase("when given title and date separated by periods", "Home Movie 2012.12.12", "Home Movie 2012.12.12"),
                new TestCase("when given title and date separated by dashes", "Home Movie 2012-12-12", "Home Movie 2012-12-12"),
                new TestCase("when given title with 4k exclusion", "Known.Exclusion", "Known.Exclusion.4k"),
                new TestCase("when given title with UltraHD exclusion", "Known.Exclusion", "Known.Exclusion.UltraHD"),
                new TestCase("when given title with UHD exclusion", "Known.Exclusion", "Known.Exclusion.UHD"),
                new TestCase("and do no formatting when given a title containing exclusion in the middle of the word", "Zuhdiyyat", "Zuhdiyyat"),
                new TestCase("when given title with HDC exclusion", "Known.Exclusion", "Known.Exclusion.HDC"),
                new TestCase("when given title with HDR exclusion", "Known.Exclusion", "Known.Exclusion.HDR"),
                new TestCase("when given title with BDrip exclusion", "Known.Exclusion", "Known.Exclusion.BDrip"),
                new TestCase("when given title with BDrip + HDC exclusions", "Known.Exclusion", "Known.Exclusion.BDrip-HDC"),
                new TestCase("when given title with 4K + UltraHD + HDR + BDrip + HDC exclusions", "Known.Exclusion", "Known.Exclusion.4K.UltraHD.HDR.BDrip-HDC")
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = videoFilenameExtractor.extractAndClean(testCase.filename());

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
                    var result = videoFilenameExtractor.extractAndClean(testCase.input());

                    assertTrue(result.isEmpty());
                })
            );
        }
    }
}
