package com.streamarr.server.services.parsers.show;


import com.streamarr.server.services.parsers.MetadataParser;
import com.streamarr.server.services.parsers.show.regex.EpisodeRegexFixtures;
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
@DisplayName("Episode Path Metadata Parsing Tests")
public class EpisodePathMetadataParserTest {

    private final EpisodeRegexFixtures episodeRegexFixtures = new EpisodeRegexFixtures();
    private final MetadataParser<EpisodePathResult> episodePathExtractionService = new EpisodePathMetadataParser(episodeRegexFixtures);

    @Nested
    @DisplayName("Should successfully extract everything: series name, season, and episode")
    public class SuccessfulExtractionTests {

        record TestCase(String filename, String seriesName, int season, int episode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("/media/Foo/Foo-S01E01", "Foo", 1, 1),
                new TestCase("/media/Foo - S04E011", "Foo", 4, 11),
                new TestCase("/media/Foo/Foo s01x01", "Foo", 1, 1),
                new TestCase("/media/Foo (2019)/Season 4/Foo 2019.S04E03", "Foo 2019", 4, 3),
                new TestCase("/media/Foo (2019)/Season 4/Foo (2019).S04E03", "Foo (2019)", 4, 3),

                new TestCase("D:\\\\media\\\\Foo-S01E01", "Foo", 1, 1),
                new TestCase("D:\\\\media\\\\Foo - S04E011", "Foo", 4, 11),
                new TestCase("D:\\\\media\\\\Foo\\\\Foo s01x01", "Foo", 1, 1),
                new TestCase("D:\\\\media\\\\Foo (2019)\\\\Season 4\\\\Foo 2019.S04E03", "Foo 2019", 4, 3),
                new TestCase("D:\\\\media\\\\Foo (2019)\\\\Season 4\\\\Foo (2019).S04E03", "Foo (2019)", 4, 3),

                new TestCase("/Season 1/seriesname S01E02 blah", "seriesname", 1, 2),
                new TestCase("/Season 1/seriesname 01x02 blah", "seriesname", 1, 2),
                new TestCase("/Season 1/seriesname S01x02 blah", "seriesname", 1, 2),
                new TestCase("/Season 1/seriesname S01xE02 blah", "seriesname", 1, 2),

                new TestCase("/Season 2009/seriesname 2009x02 blah", "seriesname", 2009, 2),
                new TestCase("/Season 2009/seriesname S2009E02 blah", "seriesname", 2009, 2),
                new TestCase("/Season 2009/seriesname S2009x02 blah", "seriesname", 2009, 2),
                new TestCase("/Season 2009/seriesname S2009xE02 blah", "seriesname", 2009, 2),

                new TestCase("/server/anything_s01e02", "anything", 1, 2),
                new TestCase("/server/anything_s1e2", "anything", 1, 2),
                new TestCase("/server/anything_s01.e02", "anything", 1, 2),
                new TestCase("/server/anything_1x02", "anything", 1, 2),
                new TestCase("/server/anything_102", "anything", 1, 2),

                new TestCase("/Running Man/Running Man S2017E368", "Running Man", 2017, 368),
                new TestCase("/server/The Walking Dead 4x01", "The Walking Dead", 4, 1),
                new TestCase("Series/LA X, Pt. 1_s06e32", "LA X, Pt. 1", 6, 32),

                new TestCase("/server/the_simpsons-s02e01_18536.mp4", "the_simpsons", 2, 1),
                new TestCase("/Season 25/The Simpsons.S25E09.Steal this episode.mp4", "The Simpsons", 25, 9),
                new TestCase("/The Wonder Years/The.Wonder.Years.S04.PDTV.x264-JCH/The Wonder Years s04e07 Christmas Party NTSC PDTV.avi", "The Wonder Years", 4, 7),
                new TestCase("/Foo/The.Series.Name.S01E04.WEBRip.x264-Baz[Bar]/the.series.name.s01e04.webrip.x264-Baz[Bar].mkv", "the.series.name", 1, 4),
                new TestCase("Love.Death.and.Robots.S01.1080p.NF.WEB-DL.DDP5.1.x264-NTG/Love.Death.and.Robots.S01E01.Sonnies.Edge.1080p.NF.WEB-DL.DDP5.1.x264-NTG.mkv", "Love.Death.and.Robots", 1, 1),

                // TODO: name
                new TestCase("The Simpsons/The Simpsons.S25E08.Steal this episode.mp4", "The Simpsons", 25, 8),
                new TestCase("Case Closed (1996-2007)/Case Closed - 317.mkv", "Case Closed", 3, 17)

//                [InlineData("The Wonder Years/The.Wonder.Years.S04.PDTV.x264-JCH/The Wonder Years s04e07 Christmas Party NTSC PDTV.avi", 7)]
//                [InlineData("Running Man/Running Man S2017E368.mkv", 368)]
//                [InlineData("Season 2/[HorribleSubs] Hunter X Hunter - 136 [720p].mkv", 136)] // triple digit episode number
//                [InlineData("Log Horizon 2/[HorribleSubs] Log Horizon 2 - 03 [720p].mkv", 3)] // digit in series name
//                [InlineData("Season 1/seriesname 05.mkv", 5)] // no hyphen between series name and episode number
//                [InlineData("[BBT-RMX] Ranma ½ - 154 [50AC421A].mkv", 154)] // hyphens in the pre-name info, triple digit episode number
//                [InlineData("Season 2/Episode 21 - 94 Meetings.mp4", 21)] // Title starts with a number
//                [InlineData("/The.Legend.of.Condor.Heroes.2017.V2.web-dl.1080p.h264.aac-hdctv/The.Legend.of.Condor.Heroes.2017.E07.V2.web-dl.1080p.h264.aac-hdctv.mkv", 7)]
//                [InlineData("Case Closed (1996-2007)/Case Closed - 317.mkv", 317)] // triple digit episode number
//                TODO: [InlineData("Season 2/16 12 Some Title.avi", 16)]
//                TODO: [InlineData("Season 4/Uchuu.Senkan.Yamato.2199.E03.avi", 3)]
//                TODO: [InlineData("Season 2/7 12 Angry Men.avi", 7)]
//                TODO: [InlineData("Season 02/02x03x04x15 - Ep Name.mp4", 2)]
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = episodePathExtractionService.parse(testCase.filename()).orElseThrow();

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

        record TestCase(String filename, String seriesName, int episode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("[YuiSubs] Tensura Nikki - Tensei Shitara Slime Datta Ken/[YuiSubs] Tensura Nikki - Tensei Shitara Slime Datta Ken - 12 (NVENC H.265 1080p).mkv", "Tensura Nikki - Tensei Shitara Slime Datta Ken", 12),
                new TestCase("[Baz-Bar]Foo - 01 - 12[1080p][Multiple Subtitle]/[Baz-Bar] Foo - 05 [1080p][Multiple Subtitle].mkv", "Foo", 5),
                new TestCase("[tag] Foo - 1", "Foo", 1),
                new TestCase("[Baz-Bar]Foo - [1080p][Multiple Subtitle]/[Baz-Bar] Foo - 05 [1080p][Multiple Subtitle].mkv", "Foo", 5),
                new TestCase("Season 2 /[HorribleSubs] Hunter X Hunter - 136[720p].mkv", "Hunter X Hunter", 136),
                new TestCase("/Season 1/foo 06-06", "foo", 6),

                // Absolute episode number
                new TestCase("The Simpsons/The Simpsons 12.avi", "The Simpsons", 12),
                new TestCase("The Simpsons/The Simpsons 82.avi", "The Simpsons", 82),
                new TestCase("The Simpsons/The Simpsons 112.avi", "The Simpsons", 112),
                new TestCase("The Simpsons/The Simpsons 889.avi", "The Simpsons", 889),
                new TestCase("The Simpsons/The Simpsons 101.avi", "The Simpsons", 101)

            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = episodePathExtractionService.parse(testCase.filename()).orElseThrow();

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

        record TestCase(String filename, String seriesName, LocalDate date) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("/server/anything_1996.11.14", "anything", LocalDate.of(1996, 11, 14)),
                new TestCase("/server/anything_1996-11-14", "anything", LocalDate.of(1996, 11, 14))

                // TODO: Fix
//                new TestCase("when given complex filename and YYYY.MM.dd date format", "/server/james.corden.2017.04.20.anne.hathaway.720p.hdtv.x264-crooks", "james.corden", LocalDate.of(2017, 4, 20)),
//                new TestCase("when given complex filename and YYYY_MM_dd date format", "/server/ABC News 2018_03_24_19_00_00", "ABC News", LocalDate.of(2018, 4, 20))


                //        // TODO: [InlineData(@"/server/anything_14.11.1996.mp4", "anything", 1996, 11, 14)]
                //        // TODO: [InlineData(@"/server/A Daily Show - (2015-01-15) - Episode Name - [720p].mkv", "A Daily Show", 2015, 01, 15)]
                //        // TODO: [InlineData(@"/server/Last Man Standing_KTLADT_2018_05_25_01_28_00.wtv", "Last Man Standing", 2018, 05, 25)]
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = episodePathExtractionService.parse(testCase.filename()).orElseThrow();

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

        record TestCase(String filename, int season, int episode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("Series/4-12 - The Woman.mp4", 4, 12),
                new TestCase("Series/4x12 - The Woman", 4, 12),

                new TestCase("1-12 episode name", 1, 12),
                new TestCase("/server/Temp/S01E02 foo", 1, 2),

                new TestCase("Season 2009/2009x02 blah.avi", 2009, 2),
                new TestCase("Season 2009/S2009x02 blah.avi", 2009, 2),
                new TestCase("Season 2009/S2009E02 blah.avi", 2009, 2),
                new TestCase("Season 2009/S2009xE02 blah.avi", 2009, 2),

                new TestCase("Season 1/01x02 blah.avi", 1, 2),
                new TestCase("Season 1/S01x02 blah.avi", 1, 2),
                new TestCase("Season 1/S01E02 blah.avi", 1, 2),
                new TestCase("Season 1/S01xE02 blah.avi", 1, 2),

                new TestCase("Season 1/02 - blah.avi", 1, 2),
                new TestCase("Season 2/02 - blah 14 blah.avi", 2, 2),
                new TestCase("Season 1/02 - blah-02 a.avi", 1, 2),
                new TestCase("Season 2/02.avi", 2, 2)

                //                [InlineData("Season 2/2. Infestation.avi", 2)]

            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = episodePathExtractionService.parse(testCase.filename()).orElseThrow();

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
    @DisplayName("Should successfully extract only episode number")
    public class SuccessfulEpisodeTests {

        record TestCase(String filename, int episode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                // TODO: implement
                //        // TODO: [InlineData(2, @"The Simpsons/The Simpsons 5 - 02 - Ep Name.avi")]
                //        // TODO: [InlineData(2, @"The Simpsons/The Simpsons 5 - 02 Ep Name.avi")]
                //        // TODO: [InlineData(7, @"Seinfeld/Seinfeld 0807 The Checks.avi")]
                //        // This is not supported anymore after removing the episode number 365+ hack from EpisodePathParser
                //        // TODO: [InlineData(13, @"Case Closed (1996-2007)/Case Closed - 13.mkv")]

                new TestCase("The Simpsons/The Simpsons - 02 - Ep Name.avi", 2),
                new TestCase("The Simpsons/02.avi", 2),
                new TestCase("The Simpsons/02 - Ep Name.avi", 2),
                new TestCase("The Simpsons/02-Ep Name.avi", 2),
                new TestCase("The Simpsons/02.EpName.avi", 2),
                new TestCase("The Simpsons/The Simpsons - 02.avi", 2),
                new TestCase("The Simpsons/The Simpsons - 02 Ep Name.avi", 2),
                new TestCase("GJ Club (2013)/GJ Club - 07.mkv", 7),

                // Absolute episode number
                new TestCase("The Simpsons/12.avi", 12),
                new TestCase("The Simpsons/Foo_ep_02.avi", 2)

            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = episodePathExtractionService.parse(testCase.filename()).orElseThrow();

                    assertThat(result.getSeasonNumber()).isEmpty();
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

        record TestCase(String filename, String seriesName, int endingEpisode) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("Season 02/Elementary - 02x03x04x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("Season 2/Elementary - 02x03 - 02x04 - 02x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("Season 2/02x03 - 02x04 - 02x15 - Ep Name.mp4", null, 15),
                new TestCase("Season 2/02x03-04-15 - Ep Name.mp4", null, 15),
                new TestCase("Season 1/S01E23-E24-E26 - The Woman.mp4", null, 26),
                new TestCase("Season 02/02x03-E15 - Ep Name.mp4", null, 15),
                new TestCase("Season 2/Elementary - 02x03-04-15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("Season 1/Elementary - S01E23-E24-E26 - The Woman.mp4", "Elementary", 26),
                new TestCase("Season 02/Elementary - 02x03-E15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("Season 02/02x03 - x04 - x15 - Ep Name.mp4", null, 15),
                new TestCase("Season 02/Elementary - 02x03 - x04 - x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("Season 02/02x03x04x15 - Ep Name.mp4", null, 15),

                new TestCase("Season 2009/Elementary - 2009x03x04x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("Season 2009/Elementary - 2009x03 - 2009x04 - 2009x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("Season 2009/2009x03 - 2009x04 - 2009x15 - Ep Name.mp4", null, 15),
                new TestCase("Season 2009/2009x03-04-15 - Ep Name.mp4", null, 15),
                new TestCase("Season 2009/S2009E23-E24-E26 - The Woman.mp4", null, 26),
                new TestCase("Season 2009/2009x03-E15 - Ep Name.mp4", null, 15),
                new TestCase("Season 2009/Elementary - 2009x03-04-15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("Season 2009/Elementary - S2009E23-E24-E26 - The Woman.mp4", "Elementary", 26),
                new TestCase("Season 2009/Elementary - 2009x03-E15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("Season 2009/2009x03 - x04 - x15 - Ep Name.mp4", null, 15),
                new TestCase("Season 2009/Elementary - 2009x03 - x04 - x15 - Ep Name.mp4", "Elementary", 15),
                new TestCase("Season 2009/02x03x04x15 - Ep Name.mp4", null, 15),

                new TestCase("/Season 1/foo 03-06", "foo", 6),

                new TestCase("Season 1/02-03 - blah.avi", null, 3),
                new TestCase("Season 2/02-04 - blah 14 blah.avi", null, 4),
                new TestCase("Season 1/02-05 - blah-02 a.avi", null, 5),
                new TestCase("Season 2/02-04.avi", null, 4),

                new TestCase("Season 1/MOONLIGHTING_s01e01-e04", "MOONLIGHTING", 4)
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = episodePathExtractionService.parse(testCase.filename()).orElseThrow();

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

        record TestCase(String filename) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("/series-s09e14-1080p.mkv"),
                new TestCase("Season 1/series-s09e14-1080i.mkv"),
                new TestCase("Season 1/series-s09e14-720p.mkv"),
                new TestCase("Season 1/series-s09e14-720i.mkv"),

                new TestCase("Season 2/02x03 - 04 Ep Name.mp4"),
                new TestCase("Season 2/My show name 02x03 - 04 Ep Name.mp4"),
                new TestCase("Season 1/4x01 – 20 Hours in America (1).mkv")
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = episodePathExtractionService.parse(testCase.filename()).orElseThrow();

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

        record TestCase(String filename) {
        }

        @TestFactory
        Stream<DynamicNode> tests() {
            return Stream.of(
                new TestCase("/server/failure_s2501e01"),
                new TestCase("/server/failure_s201e01")
            ).map(testCase -> DynamicTest.dynamicTest(
                testCase.filename(),
                () -> {
                    var result = episodePathExtractionService.parse(testCase.filename());

                    assertThat(result).isEmpty();
                })
            );
        }
    }
}
