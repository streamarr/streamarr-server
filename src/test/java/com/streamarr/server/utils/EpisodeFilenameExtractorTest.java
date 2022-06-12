package com.streamarr.server.utils;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.time.LocalDate;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("UnitTest")
@DisplayName("Episode Filename Extractor Tests")
public class EpisodeFilenameExtractorTest {

    private final EpisodeRegexConfig episodeRegexConfig = new EpisodeRegexConfig();
    private final EpisodeFilenameExtractor episodeFilenameExtractor = new EpisodeFilenameExtractor(episodeRegexConfig);

    @Nested
    @DisplayName("Should successfully extract title, season, and episode")
    public class SuccessfulExtractionTests {

        record TestCase(String name, String filename, String title, int season, int episode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given path, title, and S01E01 format separated by '-'", "/media/Foo/Foo-S01E01", "Foo", 1, 1),
                new TestCase("when given path, title, and S01E01 format separated by ' - '", "/media/Foo - S04E011", "Foo", 4, 11),
                new TestCase("when given path, title, and S01x01 format", "/media/Foo/Foo s01x01", "Foo", 1, 1),
                new TestCase("when given path, title containing year, and S01E01 format", "/media/Foo (2019)/Season 4/Foo 2019.S04E03", "Foo 2019", 4, 3),
                new TestCase("when given path, title containing year surrounded by parenthesis, and S01E01 format", "/media/Foo (2019)/Season 4/Foo (2019).S04E03", "Foo (2019)", 4, 3),

                new TestCase("when given windows path, title, and S01E01 format separated by '-'", "D:\\\\media\\\\Foo-S01E01", "Foo", 1, 1),
                new TestCase("when given windows path, title, and S01E01 format separated by ' - '", "D:\\\\media\\\\Foo - S04E011", "Foo", 4, 11),
                new TestCase("when given windows path, title, and S01x01 format", "D:\\\\media\\\\Foo\\\\Foo s01x01", "Foo", 1, 1),
                new TestCase("when given windows path, title containing year, and S01E01 format", "D:\\\\media\\\\Foo (2019)\\\\Season 4\\\\Foo 2019.S04E03", "Foo 2019", 4, 3),
                new TestCase("when given windows path, title containing year surrounded by parenthesis, and S01E01 format", "D:\\\\media\\\\Foo (2019)\\\\Season 4\\\\Foo (2019).S04E03", "Foo (2019)", 4, 3),

                // TODO: Name these
                new TestCase("...21", "/Season 2/Elementary - 02x03-04-15 - Ep Name", "Elementary", 2, 3),
                new TestCase("...22", "/Season 2/Elementary - 02x03 - 02x04 - 02x15 - Ep Name", "Elementary", 2, 3),
                new TestCase("...23", "/Season 02/Elementary - 02x03 - x04 - x15 - Ep Name", "Elementary", 2, 3),
                new TestCase("...24", "/Season 02/Elementary - 02x03x04x15 - Ep Name", "Elementary", 2, 3),
                new TestCase("...25", "/Season 02/Elementary - 02x03-E15 - Ep Name", "Elementary", 2, 3),
                new TestCase("...26", "/Season 1/Elementary - S01E23-E24-E26 - The Woman", "Elementary", 1, 23),

                new TestCase("...3", "/Running Man/Running Man S2017E368", "Running Man", 2017, 368),
                new TestCase("...3", "/Season 1/seriesname S01E02 blah", "seriesname", 1, 2),
                new TestCase("...3", "/Season 1/seriesname 01x02 blah", "seriesname", 1, 2),
                new TestCase("...3", "/Season 1/seriesname S01x02 blah", "seriesname", 1, 2),
                new TestCase("...3", "/Season 1/seriesname S01xE02 blah", "seriesname", 1, 2),

                new TestCase("...4", "/server/anything_s01e02", "anything", 1, 2),

                new TestCase("...4", "/server/anything_s1e2", "anything", 1, 2),
                new TestCase("...4", "/server/anything_s01.e02", "anything", 1, 2),
                new TestCase("...4", "/server/anything_1x02", "anything", 1, 2),
                new TestCase("...4", "/server/anything_102", "anything", 1, 2),

                new TestCase("...5", "/server/The Walking Dead 4x01", "The Walking Dead", 4, 1),
                new TestCase("...5", "Series/LA X, Pt. 1_s06e32", "LA X, Pt. 1", 6, 32),

                new TestCase("...61", "/server/the_simpsons-s02e01_18536.mp4", "the_simpsons", 2, 1),
                new TestCase("...62", "/Season 25/The Simpsons.S25E09.Steal this episode.mp4", "The Simpsons", 25, 9),
                new TestCase("...63", "/The Wonder Years/The.Wonder.Years.S04.PDTV.x264-JCH/The Wonder Years s04e07 Christmas Party NTSC PDTV.avi", "The Wonder Years", 4, 7),
                new TestCase("...64", "/Foo/The.Series.Name.S01E04.WEBRip.x264-Baz[Bar]/the.series.name.s01e04.webrip.x264-Baz[Bar].mkv", "the.series.name", 1, 4),
                new TestCase("...65", "Love.Death.and.Robots.S01.1080p.NF.WEB-DL.DDP5.1.x264-NTG/Love.Death.and.Robots.S01E01.Sonnies.Edge.1080p.NF.WEB-DL.DDP5.1.x264-NTG.mkv", "Love.Death.and.Robots", 1, 1)
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename()).orElseThrow();

                    assertThat(result.getEpisodeNumber().orElseThrow()).isEqualTo(testCase.episode());
                    assertThat(result.getSeasonNumber().orElseThrow()).isEqualTo(testCase.season());
                    assertThat(result.getSeriesName()).isEqualTo(testCase.title());
                    assertTrue(result.isSuccess());
                })
            );
        }
    }

    @Nested
    @DisplayName("Should successfully extract title and episode")
    public class SuccessfulEpisodeTests {

        record TestCase(String name, String filename, String title, int episode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given multiple tags in path and tricky filename", "[YuiSubs] Tensura Nikki - Tensei Shitara Slime Datta Ken/[YuiSubs] Tensura Nikki - Tensei Shitara Slime Datta Ken - 12 (NVENC H.265 1080p).mkv", "Tensura Nikki - Tensei Shitara Slime Datta Ken", 12),
                new TestCase("when given multiple tags in path and tricky numbering", "[Baz-Bar]Foo - 01 - 12[1080p][Multiple Subtitle]/[Baz-Bar] Foo - 05 [1080p][Multiple Subtitle].mkv", "Foo", 5),
                new TestCase("when given tag before title and episode separated by dash", "[tag] Foo - 1", "Foo", 1),
                new TestCase("when given title and episode containing many tags", "[Baz-Bar]Foo - [1080p][Multiple Subtitle]/[Baz-Bar] Foo - 05 [1080p][Multiple Subtitle].mkv", "Foo", 5)
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename()).orElseThrow();

                    assertThat(result.getSeasonNumber()).isEmpty();
                    assertThat(result.getEpisodeNumber().orElseThrow()).isEqualTo(testCase.episode());
                    assertThat(result.getSeriesName()).isEqualTo(testCase.title());
                    assertTrue(result.isSuccess());
                })
            );
        }
    }

    @Nested
    @DisplayName("Should successfully extract title and date")
    public class SuccessfulDateExtractionTests {

        record TestCase(String name, String filename, String title, LocalDate date) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given title and YYYY-MM-dd date format", "/server/anything_1996.11.14", "anything", LocalDate.of(1996, 11, 14))
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename()).orElseThrow();

                    assertThat(result.getDate()).isEqualTo(testCase.date());
                    assertThat(result.getSeriesName()).isEqualTo(testCase.title());
                    assertTrue(result.isSuccess());
                })
            );
        }
    }

    @Nested
    @DisplayName("Should successfully extract season and episode, no title")
    public class SuccessfulSeasonEpisodeTests {

        record TestCase(String name, String filename, int season, int episode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                // TODO: Fix
//                new TestCase("failing", "Series/4-12 - The Woman.mp4", 4, 12),
                new TestCase("when given SxE format", "Series/4x12 - The Woman", 4, 12),
                new TestCase("when given S-E format", "1-12 episode title", 1, 12),
                new TestCase("when given S01E01 format", "/server/Temp/S01E02 foo", 1, 2)
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename()).orElseThrow();

                    assertThat(result.getEpisodeNumber().orElseThrow()).isEqualTo(testCase.episode());
                    assertThat(result.getSeasonNumber().orElseThrow()).isEqualTo(testCase.season());
                    assertThat(result.getSeriesName()).isNull();
                    assertTrue(result.isSuccess());
                })
            );
        }
    }

    @Nested
    @DisplayName("Should extract ending episode")
    public class SuccessfulMultiEpisodeExtractionTests {

        record TestCase(String name, String filename, OptionalInt endingEpisode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given filename containing multiple episodes", "Season 02/Elementary - 02x03x04x15 - Ep Name.mp4", OptionalInt.of(15)),
                // TODO: Should this be in it's own nested?
                new TestCase("shouldn't extract 1080p", "/series-s09e14-1080p.mkv", OptionalInt.empty())
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename()).orElseThrow();

                    assertThat(result.getEndingEpisodeNumber()).isEqualTo(testCase.endingEpisode());
                    assertTrue(result.isSuccess());
                })
            );
        }
    }

    @Nested
    @DisplayName("Should fail extraction")
    public class ExtractionFailureTests {

        record TestCase(String name, String filename) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given season number above 2500", "/server/failure_s2501e01"),
                new TestCase("when given season number between 200 - 1927", "/server/failure_s201e01")
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename());

                    assertThat(result).isEmpty();
                })
            );
        }
    }
}
