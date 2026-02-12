package com.streamarr.server.services.parsers.show;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.parsers.MetadataParser;
import com.streamarr.server.services.parsers.show.regex.EpisodeRegexFixtures;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

@Tag("UnitTest")
@DisplayName("Episode Path Metadata Parsing Tests")
class EpisodePathMetadataParserTest {

  private final EpisodeRegexFixtures episodeRegexFixtures = new EpisodeRegexFixtures();
  private final MetadataParser<EpisodePathResult> episodePathExtractionService =
      new EpisodePathMetadataParser(episodeRegexFixtures);

  @Nested
  @DisplayName("Should successfully extract everything: series name, season, and episode")
  class SuccessfulExtractionTests {

    record TestCase(String filename, String seriesName, int season, int episode) {}

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
              new TestCase(
                  "D:\\\\media\\\\Foo (2019)\\\\Season 4\\\\Foo 2019.S04E03", "Foo 2019", 4, 3),
              new TestCase(
                  "D:\\\\media\\\\Foo (2019)\\\\Season 4\\\\Foo (2019).S04E03", "Foo (2019)", 4, 3),
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
              new TestCase(
                  "/Season 25/The Simpsons.S25E09.Steal this episode.mp4", "The Simpsons", 25, 9),
              new TestCase(
                  "/The Wonder Years/The.Wonder.Years.S04.PDTV.x264-JCH/The Wonder Years s04e07 Christmas Party NTSC PDTV.avi",
                  "The Wonder Years",
                  4,
                  7),
              new TestCase(
                  "/Foo/The.Series.Name.S01E04.WEBRip.x264-Baz[Bar]/the.series.name.s01e04.webrip.x264-Baz[Bar].mkv",
                  "the.series.name",
                  1,
                  4),
              new TestCase(
                  "Love.Death.and.Robots.S01.1080p.NF.WEB-DL.DDP5.1.x264-NTG/Love.Death.and.Robots.S01E01.Sonnies.Edge.1080p.NF.WEB-DL.DDP5.1.x264-NTG.mkv",
                  "Love.Death.and.Robots",
                  1,
                  1),
              new TestCase(
                  "The Simpsons/The Simpsons.S25E08.Steal this episode.mp4", "The Simpsons", 25, 8),
              new TestCase("Case Closed (1996-2007)/Case Closed - 317.mkv", "Case Closed", 3, 17),

              // Verbose "Season X Episode Y" format
              new TestCase("/media/My Show Season 1 Episode 3.mkv", "My Show", 1, 3),
              new TestCase("/media/Show S02 Episode 05 - Title.mkv", "Show", 2, 5),
              new TestCase("/media/show season 2 episode 10.mkv", "show", 2, 10),
              new TestCase("/media/Show Season01 Episode03.mkv", "Show", 1, 3),

              // Version marker (anime fansub releases)
              new TestCase("/media/Show/Show.S01E01v2.mkv", "Show", 1, 1))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result =
                            episodePathExtractionService.parse(testCase.filename()).orElseThrow();

                        assertThat(result.getSeriesName()).isEqualTo(testCase.seriesName());
                        assertThat(result.getSeasonNumber().orElseThrow())
                            .isEqualTo(testCase.season());
                        assertThat(result.getEpisodeNumber().orElseThrow())
                            .isEqualTo(testCase.episode());
                        assertThat(result.getEndingEpisodeNumber()).isEmpty();
                        assertThat(result.getDate()).isNull();
                        assertThat(result.isSuccess()).isTrue();
                      }));
    }
  }

  @Nested
  @DisplayName("Should successfully extract series name and episode")
  class SuccessfulNameAndEpisodeTests {

    record TestCase(String filename, String seriesName, int episode) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase(
                  "[YuiSubs] Tensura Nikki - Tensei Shitara Slime Datta Ken/[YuiSubs] Tensura Nikki - Tensei Shitara Slime Datta Ken - 12 (NVENC H.265 1080p).mkv",
                  "Tensura Nikki - Tensei Shitara Slime Datta Ken",
                  12),
              new TestCase(
                  "[Baz-Bar]Foo - 01 - 12[1080p][Multiple Subtitle]/[Baz-Bar] Foo - 05 [1080p][Multiple Subtitle].mkv",
                  "Foo",
                  5),
              new TestCase("[tag] Foo - 1", "Foo", 1),
              new TestCase(
                  "[Baz-Bar]Foo - [1080p][Multiple Subtitle]/[Baz-Bar] Foo - 05 [1080p][Multiple Subtitle].mkv",
                  "Foo",
                  5),
              new TestCase(
                  "Season 2 /[HorribleSubs] Hunter X Hunter - 136[720p].mkv",
                  "Hunter X Hunter",
                  136),
              new TestCase("/Season 1/foo 06-06", "foo", 6),

              // Absolute episode number
              new TestCase("The Simpsons/The Simpsons 12.avi", "The Simpsons", 12),
              new TestCase("The Simpsons/The Simpsons 82.avi", "The Simpsons", 82),
              new TestCase("The Simpsons/The Simpsons 112.avi", "The Simpsons", 112),
              new TestCase("The Simpsons/The Simpsons 889.avi", "The Simpsons", 889),
              new TestCase("The Simpsons/The Simpsons 101.avi", "The Simpsons", 101),

              // Version marker (anime fansub releases)
              new TestCase("/media/[SubsPlease] Show - 01v2 (1080p).mkv", "Show", 1),

              // Dot-separated anime bracket naming
              new TestCase(
                  "/tv/Fullmetal Alchemist Brotherhood/Season 1/[QaS].Fullmetal.Alchemist.Brotherhood.-.01.[BD.1080p.HEVC.x265.10bit.Opus.5.1][Dual.Audio].mkv",
                  "Fullmetal.Alchemist.Brotherhood",
                  1))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result =
                            episodePathExtractionService.parse(testCase.filename()).orElseThrow();

                        assertThat(result.getSeriesName()).isEqualTo(testCase.seriesName());
                        assertThat(result.getEpisodeNumber().orElseThrow())
                            .isEqualTo(testCase.episode());
                        assertThat(result.getSeasonNumber()).isEmpty();
                        assertThat(result.getEndingEpisodeNumber()).isEmpty();
                        assertThat(result.getDate()).isNull();
                        assertThat(result.isSuccess()).isTrue();
                      }));
    }
  }

  @Nested
  @DisplayName("Should successfully extract series name and date")
  class SuccessfulDateExtractionTests {

    record TestCase(String filename, String seriesName, LocalDate date) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase("/server/anything_1996.11.14", "anything", LocalDate.of(1996, 11, 14)),
              new TestCase("/server/anything_1996-11-14", "anything", LocalDate.of(1996, 11, 14)),

              // Underscore and space delimiters (DVR recordings)
              new TestCase("/server/anything_1996_11_14", "anything", LocalDate.of(1996, 11, 14)),
              new TestCase("/server/anything 1996 11 14", "anything", LocalDate.of(1996, 11, 14)),

              // Reverse date (DD.MM.YYYY) with various delimiters
              new TestCase("/server/anything_14.11.1996", "anything", LocalDate.of(1996, 11, 14)),
              new TestCase("/server/anything_14_11_1996", "anything", LocalDate.of(1996, 11, 14)),

              // No series name (file is just a date)
              new TestCase("/server/1996.11.14", null, LocalDate.of(1996, 11, 14)),

              // Multi-word series name
              new TestCase("/server/ABC News 2018-03-24", "ABC News", LocalDate.of(2018, 3, 24)),

              // Filenames with file extensions (real-world paths from library scanning)
              new TestCase(
                  "/tv/Jeopardy!/Jeopardy! - 2025-11-25.mkv",
                  "Jeopardy!",
                  LocalDate.of(2025, 11, 25)),
              new TestCase(
                  "/tv/Daily Show/Daily Show 2020.04.17.720p.mkv",
                  "Daily Show",
                  LocalDate.of(2020, 4, 17)),
              new TestCase("/tv/anything_14.11.1996.avi", "anything", LocalDate.of(1996, 11, 14)))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result =
                            episodePathExtractionService.parse(testCase.filename()).orElseThrow();

                        assertThat(result.getSeriesName()).isEqualTo(testCase.seriesName());
                        assertThat(result.getDate()).isEqualTo(testCase.date());
                        assertThat(result.getEpisodeNumber()).isEmpty();
                        assertThat(result.getSeasonNumber()).isEmpty();
                        assertThat(result.getEndingEpisodeNumber()).isEmpty();
                        assertThat(result.isSuccess()).isTrue();
                      }));
    }
  }

  @Nested
  @DisplayName("Should successfully extract season and episode without series name")
  class SuccessfulSeasonEpisodeTests {

    record TestCase(String filename, int season, int episode) {}

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
              new TestCase("Season 2/02.avi", 2, 2),

              // Case sensitivity variants
              new TestCase("/server/Temp/s01e02 foo", 1, 2),
              new TestCase("/server/Temp/S01e02 foo", 1, 2))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result =
                            episodePathExtractionService.parse(testCase.filename()).orElseThrow();

                        assertThat(result.getSeasonNumber().orElseThrow())
                            .isEqualTo(testCase.season());
                        assertThat(result.getEpisodeNumber().orElseThrow())
                            .isEqualTo(testCase.episode());
                        assertThat(result.getEndingEpisodeNumber()).isEmpty();
                        assertThat(result.getSeriesName()).isNull();
                        assertThat(result.isSuccess()).isTrue();
                      }));
    }
  }

  @Nested
  @DisplayName("Should successfully extract only episode number")
  class SuccessfulEpisodeTests {

    record TestCase(String filename, int episode) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
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
              new TestCase("The Simpsons/Foo_ep_02.avi", 2),

              // Part number extraction
              new TestCase("/season 1/title_part_1.avi", 1),
              new TestCase("/season 1/title.part.2.avi", 2),
              new TestCase("/season 1/title-part-3.mkv", 3),
              new TestCase("/Season 1/The.Night.Of.Part.7.1080p.BluRay.x264-DEPTH.mkv", 7),
              new TestCase("/Season 1/Alias.Grace.Part.4.1080p.WEBRip.x264-aAF-xpost.mkv", 4),
              new TestCase("/Season 1/Title.PART.5.720p.mkv", 5),
              new TestCase("/Season 1/Title.Pt.3.mkv", 3),

              // E-only and Episode X patterns with full file paths
              new TestCase("/media/Show/Season 1/Show.E01.mkv", 1),
              new TestCase("/media/Show/Season 1/Episode 16.mkv", 16))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result =
                            episodePathExtractionService.parse(testCase.filename()).orElseThrow();

                        assertThat(result.getSeasonNumber()).isEmpty();
                        assertThat(result.getEpisodeNumber().orElseThrow())
                            .isEqualTo(testCase.episode());
                        assertThat(result.getEndingEpisodeNumber()).isEmpty();
                        assertThat(result.getSeriesName()).isNull();
                        assertThat(result.isSuccess()).isTrue();
                      }));
    }
  }

  @Nested
  @DisplayName("Should successfully extract ending episode")
  class SuccessfulMultiEpisodeExtractionTests {

    record TestCase(String filename, String seriesName, int endingEpisode) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase("Season 02/Elementary - 02x03x04x15 - Ep Name.mp4", "Elementary", 15),
              new TestCase(
                  "Season 2/Elementary - 02x03 - 02x04 - 02x15 - Ep Name.mp4", "Elementary", 15),
              new TestCase("Season 2/02x03 - 02x04 - 02x15 - Ep Name.mp4", null, 15),
              new TestCase("Season 2/02x03-04-15 - Ep Name.mp4", null, 15),
              new TestCase("Season 1/S01E23-E24-E26 - The Woman.mp4", null, 26),
              new TestCase("Season 02/02x03-E15 - Ep Name.mp4", null, 15),
              new TestCase("Season 2/Elementary - 02x03-04-15 - Ep Name.mp4", "Elementary", 15),
              new TestCase(
                  "Season 1/Elementary - S01E23-E24-E26 - The Woman.mp4", "Elementary", 26),
              new TestCase("Season 02/Elementary - 02x03-E15 - Ep Name.mp4", "Elementary", 15),
              new TestCase("Season 02/02x03 - x04 - x15 - Ep Name.mp4", null, 15),
              new TestCase(
                  "Season 02/Elementary - 02x03 - x04 - x15 - Ep Name.mp4", "Elementary", 15),
              new TestCase("Season 02/02x03x04x15 - Ep Name.mp4", null, 15),
              new TestCase(
                  "Season 2009/Elementary - 2009x03x04x15 - Ep Name.mp4", "Elementary", 15),
              new TestCase(
                  "Season 2009/Elementary - 2009x03 - 2009x04 - 2009x15 - Ep Name.mp4",
                  "Elementary",
                  15),
              new TestCase("Season 2009/2009x03 - 2009x04 - 2009x15 - Ep Name.mp4", null, 15),
              new TestCase("Season 2009/2009x03-04-15 - Ep Name.mp4", null, 15),
              new TestCase("Season 2009/S2009E23-E24-E26 - The Woman.mp4", null, 26),
              new TestCase("Season 2009/2009x03-E15 - Ep Name.mp4", null, 15),
              new TestCase(
                  "Season 2009/Elementary - 2009x03-04-15 - Ep Name.mp4", "Elementary", 15),
              new TestCase(
                  "Season 2009/Elementary - S2009E23-E24-E26 - The Woman.mp4", "Elementary", 26),
              new TestCase("Season 2009/Elementary - 2009x03-E15 - Ep Name.mp4", "Elementary", 15),
              new TestCase("Season 2009/2009x03 - x04 - x15 - Ep Name.mp4", null, 15),
              new TestCase(
                  "Season 2009/Elementary - 2009x03 - x04 - x15 - Ep Name.mp4", "Elementary", 15),
              new TestCase("Season 2009/02x03x04x15 - Ep Name.mp4", null, 15),
              new TestCase("/Season 1/foo 03-06", "foo", 6),
              new TestCase("Season 1/02-03 - blah.avi", null, 3),
              new TestCase("Season 2/02-04 - blah 14 blah.avi", null, 4),
              new TestCase("Season 1/02-05 - blah-02 a.avi", null, 5),
              new TestCase("Season 2/02-04.avi", null, 4),
              new TestCase("Season 1/MOONLIGHTING_s01e01-e04", "MOONLIGHTING", 4))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result =
                            episodePathExtractionService.parse(testCase.filename()).orElseThrow();

                        assertThat(result.getSeriesName()).isEqualTo(testCase.seriesName());
                        assertThat(result.getEndingEpisodeNumber().orElseThrow())
                            .isEqualTo(testCase.endingEpisode());
                        assertThat(result.getDate()).isNull();
                        assertThat(result.isSuccess()).isTrue();
                      }));
    }
  }

  @Nested
  @DisplayName("Should not extract ending episode")
  class FailedMultiEpisodeExtractionTests {

    record TestCase(String filename, int season, int episode) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase("/series-s09e14-1080p.mkv", 9, 14),
              new TestCase("Season 1/series-s09e14-1080i.mkv", 9, 14),
              new TestCase("Season 1/series-s09e14-720p.mkv", 9, 14),
              new TestCase("Season 1/series-s09e14-720i.mkv", 9, 14),
              new TestCase("Season 2/02x03 - 04 Ep Name.mp4", 2, 3),
              new TestCase("Season 2/My show name 02x03 - 04 Ep Name.mp4", 2, 3),
              new TestCase("Season 1/4x01 – 20 Hours in America (1).mkv", 4, 1))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result =
                            episodePathExtractionService.parse(testCase.filename()).orElseThrow();

                        assertThat(result.getSeasonNumber().orElseThrow())
                            .isEqualTo(testCase.season());
                        assertThat(result.getEpisodeNumber().orElseThrow())
                            .isEqualTo(testCase.episode());
                        assertThat(result.getEndingEpisodeNumber()).isEmpty();
                        assertThat(result.getDate()).isNull();
                        assertThat(result.isSuccess()).isTrue();
                      }));
    }
  }

  @Nested
  @DisplayName("Should correctly validate season number boundaries")
  class SeasonValidationBoundaryTests {

    @Nested
    @DisplayName("Valid season numbers should succeed")
    class ValidSeasonNumbers {

      record TestCase(String filename, int season, int episode) {}

      @TestFactory
      Stream<DynamicNode> tests() {
        return Stream.of(
                new TestCase("/server/show_s199e01", 199, 1),
                new TestCase("/server/show_s1928e01", 1928, 1),
                new TestCase("/server/show_s2500e01", 2500, 1))
            .map(
                testCase ->
                    DynamicTest.dynamicTest(
                        testCase.filename(),
                        () -> {
                          var result =
                              episodePathExtractionService.parse(testCase.filename()).orElseThrow();

                          assertThat(result.getSeasonNumber().orElseThrow())
                              .isEqualTo(testCase.season());
                          assertThat(result.getEpisodeNumber().orElseThrow())
                              .isEqualTo(testCase.episode());
                          assertThat(result.isSuccess()).isTrue();
                        }));
      }
    }

    @Nested
    @DisplayName("Invalid season numbers should fail")
    class InvalidSeasonNumbers {

      record TestCase(String filename) {}

      @TestFactory
      Stream<DynamicNode> tests() {
        return Stream.of(
                new TestCase("/server/show_s200e01"),
                new TestCase("/server/show_s1927e01"),
                new TestCase("/server/show_s1080e720"),
                new TestCase("/server/show_s1920e1080"))
            .map(
                testCase ->
                    DynamicTest.dynamicTest(
                        testCase.filename(),
                        () -> {
                          var result = episodePathExtractionService.parse(testCase.filename());

                          assertThat(result).isEmpty();
                        }));
      }
    }
  }

  @Nested
  @DisplayName("Should fail extraction and return empty optional")
  class FailedExtractionTests {

    record TestCase(String filename) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase("/server/failure_s2501e01"), new TestCase("/server/failure_s201e01"))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result = episodePathExtractionService.parse(testCase.filename());

                        assertThat(result).isEmpty();
                      }));
    }
  }

  @Nested
  @DisplayName("Should not create phantom seasons from trailing years (Jellyfin #15011)")
  class PhantomSeasonRegressionTests {

    @Test
    @DisplayName("Should not create phantom season from trailing year in episode title")
    void shouldNotCreatePhantomSeasonFromTrailingYearInEpisodeTitle() {
      var result =
          episodePathExtractionService
              .parse("/tv/Show/Show - S01E01 - Pilot (2002).mkv")
              .orElseThrow();

      assertThat(result.getSeriesName()).isEqualTo("Show");
      assertThat(result.getSeasonNumber().orElseThrow()).isEqualTo(1);
      assertThat(result.getEpisodeNumber().orElseThrow()).isEqualTo(1);
      assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should ignore trailing year in bare episode number")
    void shouldIgnoreTrailingYearInBareEpisodeNumber() {
      var result =
          episodePathExtractionService
              .parse("/tv/Show/Season 1/02 - Episode Title (2002).mkv")
              .orElseThrow();

      assertThat(result.getSeasonNumber().orElseThrow()).isEqualTo(1);
      assertThat(result.getEpisodeNumber().orElseThrow()).isEqualTo(2);
      assertThat(result.isSuccess()).isTrue();
    }
  }
}
