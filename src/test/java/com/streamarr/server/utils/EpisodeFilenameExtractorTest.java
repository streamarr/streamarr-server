package com.streamarr.server.utils;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.time.LocalDate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("UnitTest")
@DisplayName("Episode Filename Extractor Tests")
public class EpisodeFilenameExtractorTest {

    private final EpisodeRegexConfig episodeRegexConfig = new EpisodeRegexConfig();
    private final EpisodeFilenameExtractor episodeFilenameExtractor = new EpisodeFilenameExtractor(episodeRegexConfig);

    @Nested
    @DisplayName("Should successfully extract everything: series name, season, and episode")
    public class SuccessfulExtractionTests {

        record TestCase(String name, String filename, String seriesName, int season, int episode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given filename containing path, series name, and S00E00 format separated by '-'", "/media/Foo/Foo-S01E01", "Foo", 1, 1),
                new TestCase("when given filename containing path, series name, and S00E00 format separated by ' - '", "/media/Foo - S04E011", "Foo", 4, 11),
                new TestCase("when given filename containing path, series name, and S00x00 format", "/media/Foo/Foo s01x01", "Foo", 1, 1),
                new TestCase("when given filename containing path, series name containing year, and S00E00 format", "/media/Foo (2019)/Season 4/Foo 2019.S04E03", "Foo 2019", 4, 3),
                new TestCase("when given filename containing path, series name containing year surrounded by parenthesis, and S00E00 format", "/media/Foo (2019)/Season 4/Foo (2019).S04E03", "Foo (2019)", 4, 3),

                new TestCase("when given filename containing windows path, series name, and S00E00 format separated by '-'", "D:\\\\media\\\\Foo-S01E01", "Foo", 1, 1),
                new TestCase("when given filename containing windows path, series name, and S00E00 format separated by ' - '", "D:\\\\media\\\\Foo - S04E011", "Foo", 4, 11),
                new TestCase("when given filename containing windows path, series name, and S00x00 format", "D:\\\\media\\\\Foo\\\\Foo s01x01", "Foo", 1, 1),
                new TestCase("when given filename containing windows path, series name containing year, and S00E00 format", "D:\\\\media\\\\Foo (2019)\\\\Season 4\\\\Foo 2019.S04E03", "Foo 2019", 4, 3),
                new TestCase("when given filename containing windows path, series name containing year surrounded by parenthesis, and S00E00 format", "D:\\\\media\\\\Foo (2019)\\\\Season 4\\\\Foo (2019).S04E03", "Foo (2019)", 4, 3),

                new TestCase("when given filename containing path, series name, episode name, and S00E00 format", "/Season 1/seriesname S01E02 blah", "seriesname", 1, 2),
                new TestCase("when given filename containing path, series name, episode name, and 00x00 format", "/Season 1/seriesname 01x02 blah", "seriesname", 1, 2),
                new TestCase("when given filename containing path, series name, episode name, and S00x00 format", "/Season 1/seriesname S01x02 blah", "seriesname", 1, 2),
                new TestCase("when given filename containing path, series name, episode name, and S00xE00 format", "/Season 1/seriesname S01xE02 blah", "seriesname", 1, 2),

                new TestCase("when given season with 4 digits in 0000x00 format", "/Season 2009/seriesname 2009x02 blah", "seriesname", 2009, 2),
                new TestCase("when given season with 4 digits in S0000E00 format", "/Season 2009/seriesname S2009E02 blah", "seriesname", 2009, 2),
                new TestCase("when given season with 4 digits in S0000x00 format", "/Season 2009/seriesname S2009x02 blah", "seriesname", 2009, 2),
                new TestCase("when given season with 4 digits in S0000xE00 format", "/Season 2009/seriesname S2009xE02 blah", "seriesname", 2009, 2),

                new TestCase("when given filename containing path, series name, and S00E00 format separated by '_'", "/server/anything_s01e02", "anything", 1, 2),
                new TestCase("when given filename containing path, series name, and S0E0 format separated by '_'", "/server/anything_s1e2", "anything", 1, 2),
                new TestCase("when given filename containing path, series name, and S00.E00 format separated by '_'", "/server/anything_s01.e02", "anything", 1, 2),
                new TestCase("when given filename containing path, series name, and 0x00 format separated by '_'", "/server/anything_1x02", "anything", 1, 2),
                new TestCase("when given filename containing path, series name, and 000 format separated by '_'", "/server/anything_102", "anything", 1, 2),

                new TestCase("when given filename containing path, series name, and S0000E000 format", "/Running Man/Running Man S2017E368", "Running Man", 2017, 368),
                new TestCase("when given filename containing path, series name, and 0x00 format", "/server/The Walking Dead 4x01", "The Walking Dead", 4, 1),
                new TestCase("when given tricky filename containing path, series name w/ part, and S00E00 format separated by '_'", "Series/LA X, Pt. 1_s06e32", "LA X, Pt. 1", 6, 32),

                new TestCase("when given filename containing path, series name, and erroneous number after S00E00 format", "/server/the_simpsons-s02e01_18536.mp4", "the_simpsons", 2, 1),
                new TestCase("when given filename containing path, series name, episode name, and S00E00 format separated by '.' and spaces", "/Season 25/The Simpsons.S25E09.Steal this episode.mp4", "The Simpsons", 25, 9),
                new TestCase("when given filename containing path, series name, episode name, and S00E00 format separated by '.'", "/The Wonder Years/The.Wonder.Years.S04.PDTV.x264-JCH/The Wonder Years s04e07 Christmas Party NTSC PDTV.avi", "The Wonder Years", 4, 7),
                new TestCase("when given filename containing path, tags, series name, episode name, and S00E00 format separated by '.'", "/Foo/The.Series.Name.S01E04.WEBRip.x264-Baz[Bar]/the.series.name.s01e04.webrip.x264-Baz[Bar].mkv", "the.series.name", 1, 4),
                new TestCase("when given tricky filename containing path, series name, episode name, and S00E00 format separated by '.'", "Love.Death.and.Robots.S01.1080p.NF.WEB-DL.DDP5.1.x264-NTG/Love.Death.and.Robots.S01E01.Sonnies.Edge.1080p.NF.WEB-DL.DDP5.1.x264-NTG.mkv", "Love.Death.and.Robots", 1, 1)
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename()).orElseThrow();

                    assertThat(result.getSeriesName()).isEqualTo(testCase.seriesName());
                    assertThat(result.getSeasonNumber().orElseThrow()).isEqualTo(testCase.season());
                    assertThat(result.getEpisodeNumber().orElseThrow()).isEqualTo(testCase.episode());
                    assertThat(result.getEndingEpisodeNumber()).isEmpty();
                    assertThat(result.getDate()).isNull();
                    assertThat(result.isSuccess()).isTrue();
                })
            );
        }
    }

    @Nested
    @DisplayName("Should successfully extract series name and episode")
    public class SuccessfulNameAndEpisodeTests {

        record TestCase(String name, String filename, String seriesName, int episode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given multiple tags in filename and tricky series name", "[YuiSubs] Tensura Nikki - Tensei Shitara Slime Datta Ken/[YuiSubs] Tensura Nikki - Tensei Shitara Slime Datta Ken - 12 (NVENC H.265 1080p).mkv", "Tensura Nikki - Tensei Shitara Slime Datta Ken", 12),
                new TestCase("when given multiple tags in filename and tricky episode numbering", "[Baz-Bar]Foo - 01 - 12[1080p][Multiple Subtitle]/[Baz-Bar] Foo - 05 [1080p][Multiple Subtitle].mkv", "Foo", 5),
                new TestCase("when given tag before series name and episode separated by dash", "[tag] Foo - 1", "Foo", 1),
                new TestCase("when given series name and episode containing many tags", "[Baz-Bar]Foo - [1080p][Multiple Subtitle]/[Baz-Bar] Foo - 05 [1080p][Multiple Subtitle].mkv", "Foo", 5),
                new TestCase("when given path with a tag before series name and tag after 000 episode number", "Season 2 /[HorribleSubs] Hunter X Hunter - 136[720p].mkv", "Hunter X Hunter", 136),
                new TestCase("when given duplicate episode and endingEpisode, expected to early exit in getAndValidateEndingEpisodeNumber()", "/Season 1/foo 06-06", "foo", 6)
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename()).orElseThrow();

                    assertThat(result.getSeriesName()).isEqualTo(testCase.seriesName());
                    assertThat(result.getEpisodeNumber().orElseThrow()).isEqualTo(testCase.episode());
                    assertThat(result.getSeasonNumber()).isEmpty();
                    assertThat(result.getEndingEpisodeNumber()).isEmpty();
                    assertThat(result.getDate()).isNull();
                    assertThat(result.isSuccess()).isTrue();
                })
            );
        }
    }

    @Nested
    @DisplayName("Should successfully extract series name and date")
    public class SuccessfulDateExtractionTests {

        record TestCase(String name, String filename, String seriesName, LocalDate date) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given series name and YYYY-MM-dd date format", "/server/anything_1996.11.14", "anything", LocalDate.of(1996, 11, 14))
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename()).orElseThrow();

                    assertThat(result.getSeriesName()).isEqualTo(testCase.seriesName());
                    assertThat(result.getDate()).isEqualTo(testCase.date());
                    assertThat(result.getEpisodeNumber()).isEmpty();
                    assertThat(result.getSeasonNumber()).isEmpty();
                    assertThat(result.getEndingEpisodeNumber()).isEmpty();
                    assertThat(result.isSuccess()).isTrue();
                })
            );
        }
    }

    @Nested
    @DisplayName("Should successfully extract season and episode without series name")
    public class SuccessfulSeasonEpisodeTests {

        record TestCase(String name, String filename, int season, int episode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given path and 0-00 format", "Series/4-12 - The Woman.mp4", 4, 12),
                new TestCase("when given path and 0x00 format", "Series/4x12 - The Woman", 4, 12),

                new TestCase("when given 0-0 format", "1-12 episode name", 1, 12),
                new TestCase("when given path and S00E00 format", "/server/Temp/S01E02 foo", 1, 2),

                new TestCase("when given path and 0000x00 format", "Season 2009/2009x02 blah.avi", 2009, 2),
                new TestCase("when given path and S0000x00 format", "Season 2009/S2009x02 blah.avi", 2009, 2),
                new TestCase("when given path and S0000E00 format", "Season 2009/S2009E02 blah.avi", 2009, 2),
                new TestCase("when given path and S0000xE00 format", "Season 2009/S2009xE02 blah.avi", 2009, 2),

                new TestCase("when given path and 00x00 format", "Season 1/01x02 blah.avi", 1, 2),
                new TestCase("when given path and S00x00 format", "Season 1/S01x02 blah.avi", 1, 2),
                new TestCase("when given path and S00E00 format", "Season 1/S01E02 blah.avi", 1, 2),
                new TestCase("when given path and S00xE00 format", "Season 1/S01xE02 blah.avi", 1, 2),

                new TestCase("when given path, with episode name, and 0/00 format", "Season 1/02 - blah.avi", 1, 2),
                new TestCase("when given path, with extra number in episode name, and 0/00 format ", "Season 2/02 - blah 14 blah.avi", 2, 2),
                new TestCase("when given path, with extra number in episode name (variation 2), and 0/00 format", "Season 1/02 - blah-02 a.avi", 1, 2),
                new TestCase("when given path and 0/00 format", "Season 2/02.avi", 2, 2)
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename()).orElseThrow();

                    assertThat(result.getSeasonNumber().orElseThrow()).isEqualTo(testCase.season());
                    assertThat(result.getEpisodeNumber().orElseThrow()).isEqualTo(testCase.episode());
                    assertThat(result.getEndingEpisodeNumber()).isEmpty();
                    assertThat(result.getSeriesName()).isNull();
                    assertThat(result.isSuccess()).isTrue();
                })
            );
        }
    }

    @Nested
    @DisplayName("Should successfully extract ending episode")
    public class SuccessfulMultiEpisodeExtractionTests {

        record TestCase(String name, String filename, String seriesName, int endingEpisode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given filename containing series name, episode name, and multiple episodes separated by 'x'", "Season 02/Elementary - 02x03x04x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("when given filename containing series name, episode name, and multiple episodes separated by ' - '", "Season 2/Elementary - 02x03 - 02x04 - 02x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("when given filename containing episode name and multiple episodes separated by ' - '", "Season 2/02x03 - 02x04 - 02x15 - Ep Name.mp4", null, 15),
                new TestCase("when given filename containing episode name and multiple episodes separated by '-'", "Season 2/02x03-04-15 - Ep Name.mp4", null, 15),
                new TestCase("when given filename containing episode name and multiple episodes separated by '-E'", "Season 1/S01E23-E24-E26 - The Woman.mp4", null, 26),
                new TestCase("when given filename containing episode name and two episodes separated by '-'", "Season 02/02x03-E15 - Ep Name.mp4", null, 15),
                new TestCase("when given filename containing series name, episode name, and multiple episodes separated by '-'", "Season 2/Elementary - 02x03-04-15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("when given filename containing series name, episode name, and multiple episodes separated by '-E'", "Season 1/Elementary - S01E23-E24-E26 - The Woman.mp4", "Elementary", 26),
                new TestCase("when given filename containing series name, episode name, two episodes separated by '-', and final episode marked with 'E'", "Season 02/Elementary - 02x03-E15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("when given filename containing episode name and multiple episodes separated by ' -x '", "Season 02/02x03 - x04 - x15 - Ep Name.mp4", null, 15),
                new TestCase("when given filename containing series name, episode name, and multiple episodes separated by ' -x '", "Season 02/Elementary - 02x03 - x04 - x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("when given filename containing episode name and multiple episodes separated by 'x'", "Season 02/02x03x04x15 - Ep Name.mp4", null, 15),

                new TestCase("when given filename containing series name, episode name, 4 digit season, and multiple episodes separated by 'x'", "Season 2009/Elementary - 2009x03x04x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("when given filename containing series name, episode name, 4 digit season, and multiple episodes separated by ' - '", "Season 2009/Elementary - 2009x03 - 2009x04 - 2009x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("when given filename containing episode name, 4 digit season, and multiple episodes separated by ' - '", "Season 2009/2009x03 - 2009x04 - 2009x15 - Ep Name.mp4", null, 15),
                new TestCase("when given filename containing episode name, 4 digit season, and multiple episodes separated by '-'", "Season 2009/2009x03-04-15 - Ep Name.mp4", null, 15),
                new TestCase("when given filename containing episode name, 4 digit season, and multiple episodes separated by '-E'", "Season 2009/S2009E23-E24-E26 - The Woman.mp4", null, 26),
                new TestCase("when given filename containing episode name, 4 digit season, and two episodes separated by '-'", "Season 2009/2009x03-E15 - Ep Name.mp4", null, 15),
                new TestCase("when given filename containing series name, episode name, 4 digit season, and multiple episodes separated by '-'", "Season 2009/Elementary - 2009x03-04-15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("when given filename containing series name, episode name, 4 digit season, and multiple episodes separated by '-E'", "Season 2009/Elementary - S2009E23-E24-E26 - The Woman.mp4", "Elementary", 26),
                new TestCase("when given filename containing series name, episode name, 4 digit season, and two episodes separated by '-', and final episode marked with 'E'", "Season 2009/Elementary - 2009x03-E15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("when given filename containing episode name, 4 digit season, and multiple episodes separated by ' -x '", "Season 2009/2009x03 - x04 - x15 - Ep Name.mp4", null, 15),
                new TestCase("when given filename containing series name, episode name, 4 digit season, and multiple episodes separated by ' -x '", "Season 2009/Elementary - 2009x03 - x04 - x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("when given filename containing episode name, 4 digit season, and multiple episodes separated by 'x'", "Season 2009/02x03x04x15 - Ep Name.mp4", null, 15),

                new TestCase("when given basic filename containing series name and two episodes separated by '-', expected to early exit in extract()", "/Season 1/foo 03-06", "foo", 6),

                new TestCase("when given filename containing episode name and two episodes separated by '-'", "Season 1/02-03 - blah.avi", null, 3),
                new TestCase("when given filename containing tricky double episode name and two episodes separated by '-'", "Season 2/02-04 - blah 14 blah.avi", null, 4),
                new TestCase("when given filename containing tricky episode name and multiple episodes separated by '-'", "Season 1/02-05 - blah-02 a.avi", null, 5),
                new TestCase("when given filename containing multiple episodes separated by '-'", "Season 2/02-04.avi", null, 4),

                new TestCase("when given filename containing uppercase series name and multiple episodes separated by '-'", "Season 1/MOONLIGHTING_s01e01-e04", "MOONLIGHTING", 4)
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename()).orElseThrow();

                    assertThat(result.getSeriesName()).isEqualTo(testCase.seriesName());
                    assertThat(result.getEndingEpisodeNumber().orElseThrow()).isEqualTo(testCase.endingEpisode());
                    assertThat(result.getDate()).isNull();
                    assertThat(result.isSuccess()).isTrue();
                })
            );
        }
    }

    @Nested
    @DisplayName("Should not extract ending episode")
    public class FailedMultiEpisodeExtractionTests {

        record TestCase(String name, String filename) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("when given 1080p pixel format in filename", "/series-s09e14-1080p.mkv"),
                new TestCase("when given 1080i pixel format in filename", "Season 1/series-s09e14-1080i.mkv"),
                new TestCase("when given 720p pixel format in filename", "Season 1/series-s09e14-720p.mkv"),
                new TestCase("when given 720i pixel format in filename", "Season 1/series-s09e14-720i.mkv"),

                new TestCase("when given 00 number in series name", "Season 2/02x03 - 04 Ep Name.mp4"),
                new TestCase("when given show name and 00 number in series name", "Season 2/My show name 02x03 - 04 Ep Name.mp4"),
                new TestCase("when given 00 number in series name and (1) indicating duplicate file", "Season 1/4x01 – 20 Hours in America (1).mkv")
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> {
                    var result = episodeFilenameExtractor.extract(testCase.filename()).orElseThrow();

                    // TODO: check other properties?
                    assertThat(result.getEndingEpisodeNumber()).isEmpty();
                    assertThat(result.getDate()).isNull();
                    assertThat(result.isSuccess()).isTrue();
                })
            );
        }
    }

    @Nested
    @DisplayName("Should fail extraction and return empty optional")
    public class FailedExtractionTests {

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
