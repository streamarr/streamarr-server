package com.streamarr.server.services.parsers.show;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.parsers.MetadataParser;
import com.streamarr.server.services.parsers.show.regex.EpisodeRegexFixtures;
import java.time.LocalDate;
import java.util.stream.Stream;
import lombok.Builder;
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
  @DisplayName("Complete Episode Metadata Extraction Tests")
  class SuccessfulExtractionTests {

    @Builder
    record TestCase(String filename, String seriesName, int season, int episode) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName(
        "Should extract complete episode metadata when path contains series, season, and episode")
    Stream<DynamicNode>
        shouldExtractCompleteEpisodeMetadataWhenPathContainsSeriesSeasonAndEpisode() {
      return Stream.of(
              testCase()
                  .filename("/media/Foo/Foo-S01E01")
                  .seriesName("Foo")
                  .season(1)
                  .episode(1)
                  .build(),
              testCase()
                  .filename("/media/Foo - S04E011")
                  .seriesName("Foo")
                  .season(4)
                  .episode(11)
                  .build(),
              testCase()
                  .filename("/media/Foo/Foo s01x01")
                  .seriesName("Foo")
                  .season(1)
                  .episode(1)
                  .build(),
              testCase()
                  .filename("/media/Foo (2019)/Season 4/Foo 2019.S04E03")
                  .seriesName("Foo 2019")
                  .season(4)
                  .episode(3)
                  .build(),
              testCase()
                  .filename("/media/Foo (2019)/Season 4/Foo (2019).S04E03")
                  .seriesName("Foo (2019)")
                  .season(4)
                  .episode(3)
                  .build(),
              testCase()
                  .filename("D:\\\\media\\\\Foo-S01E01")
                  .seriesName("Foo")
                  .season(1)
                  .episode(1)
                  .build(),
              testCase()
                  .filename("D:\\\\media\\\\Foo - S04E011")
                  .seriesName("Foo")
                  .season(4)
                  .episode(11)
                  .build(),
              testCase()
                  .filename("D:\\\\media\\\\Foo\\\\Foo s01x01")
                  .seriesName("Foo")
                  .season(1)
                  .episode(1)
                  .build(),
              testCase()
                  .filename("D:\\\\media\\\\Foo (2019)\\\\Season 4\\\\Foo 2019.S04E03")
                  .seriesName("Foo 2019")
                  .season(4)
                  .episode(3)
                  .build(),
              testCase()
                  .filename("D:\\\\media\\\\Foo (2019)\\\\Season 4\\\\Foo (2019).S04E03")
                  .seriesName("Foo (2019)")
                  .season(4)
                  .episode(3)
                  .build(),
              testCase()
                  .filename("/Season 1/seriesname S01E02 blah")
                  .seriesName("seriesname")
                  .season(1)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/Season 1/seriesname 01x02 blah")
                  .seriesName("seriesname")
                  .season(1)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/Season 1/seriesname S01x02 blah")
                  .seriesName("seriesname")
                  .season(1)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/Season 1/seriesname S01xE02 blah")
                  .seriesName("seriesname")
                  .season(1)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/Season 2009/seriesname 2009x02 blah")
                  .seriesName("seriesname")
                  .season(2009)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/Season 2009/seriesname S2009E02 blah")
                  .seriesName("seriesname")
                  .season(2009)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/Season 2009/seriesname S2009x02 blah")
                  .seriesName("seriesname")
                  .season(2009)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/Season 2009/seriesname S2009xE02 blah")
                  .seriesName("seriesname")
                  .season(2009)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/server/anything_s01e02")
                  .seriesName("anything")
                  .season(1)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/server/anything_s1e2")
                  .seriesName("anything")
                  .season(1)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/server/anything_s01.e02")
                  .seriesName("anything")
                  .season(1)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/server/anything_1x02")
                  .seriesName("anything")
                  .season(1)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/server/anything_102")
                  .seriesName("anything")
                  .season(1)
                  .episode(2)
                  .build(),
              testCase()
                  .filename("/Harbor Relay/Harbor Relay S2017E368")
                  .seriesName("Harbor Relay")
                  .season(2017)
                  .episode(368)
                  .build(),
              testCase()
                  .filename("/server/The Wandering Peak 4x01")
                  .seriesName("The Wandering Peak")
                  .season(4)
                  .episode(1)
                  .build(),
              testCase()
                  .filename("Series/Bay Q, Pt. 1_s06e32")
                  .seriesName("Bay Q, Pt. 1")
                  .season(6)
                  .episode(32)
                  .build(),
              testCase()
                  .filename("/server/the_greenfields-s02e01_18536.mp4")
                  .seriesName("the_greenfields")
                  .season(2)
                  .episode(1)
                  .build(),
              testCase()
                  .filename("/Season 25/The Greenfields.S25E09.Mend this antenna.mp4")
                  .seriesName("The Greenfields")
                  .season(25)
                  .episode(9)
                  .build(),
              testCase()
                  .filename(
                      "/The Meadow Years/The.Meadow.Years.S04.PDTV.x264-VXK/The Meadow Years s04e07 Christmas Party NTSC PDTV.avi")
                  .seriesName("The Meadow Years")
                  .season(4)
                  .episode(7)
                  .build(),
              testCase()
                  .filename(
                      "/Foo/The.Series.Name.S01E04.WEBRip.x264-Baz[Bar]/the.series.name.s01e04.webrip.x264-Baz[Bar].mkv")
                  .seriesName("the.series.name")
                  .season(1)
                  .episode(4)
                  .build(),
              testCase()
                  .filename(
                      "Rust.Dust.and.Orbits.S01.1080p.NF.WEB-DL.DDP5.1.x264-QRZ/Rust.Dust.and.Orbits.S01E01.Copper.Ledge.1080p.NF.WEB-DL.DDP5.1.x264-QRZ.mkv")
                  .seriesName("Rust.Dust.and.Orbits")
                  .season(1)
                  .episode(1)
                  .build(),
              testCase()
                  .filename("The Greenfields/The Greenfields.S25E08.Mend this antenna.mp4")
                  .seriesName("The Greenfields")
                  .season(25)
                  .episode(8)
                  .build(),
              testCase()
                  .filename("Cold Ledger (1996-2007)/Cold Ledger - 317.mkv")
                  .seriesName("Cold Ledger")
                  .season(3)
                  .episode(17)
                  .build(),

              // Verbose "Season X Episode Y" format
              testCase()
                  .filename("/media/My Show Season 1 Episode 3.mkv")
                  .seriesName("My Show")
                  .season(1)
                  .episode(3)
                  .build(),
              testCase()
                  .filename("/media/Show S02 Episode 05 - Title.mkv")
                  .seriesName("Show")
                  .season(2)
                  .episode(5)
                  .build(),
              testCase()
                  .filename("/media/show season 2 episode 10.mkv")
                  .seriesName("show")
                  .season(2)
                  .episode(10)
                  .build(),
              testCase()
                  .filename("/media/Show Season01 Episode03.mkv")
                  .seriesName("Show")
                  .season(1)
                  .episode(3)
                  .build(),

              // Version marker (anime fansub releases)
              testCase()
                  .filename("/media/Show/Show.S01E01v2.mkv")
                  .seriesName("Show")
                  .season(1)
                  .episode(1)
                  .build())
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
  @DisplayName("Series Name and Episode Extraction Tests")
  class SuccessfulNameAndEpisodeTests {

    @Builder
    record TestCase(String filename, String seriesName, int episode) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should extract series name and episode when path omits season")
    Stream<DynamicNode> shouldExtractSeriesNameAndEpisodeWhenPathOmitsSeason() {
      return Stream.of(
              testCase()
                  .filename(
                      "[MokoSubs] Suzu Nikki - Yama no Machi no Hotaru/[MokoSubs] Suzu Nikki - Yama no Machi no Hotaru - 12 (NVENC H.265 1080p).mkv")
                  .seriesName("Suzu Nikki - Yama no Machi no Hotaru")
                  .episode(12)
                  .build(),
              testCase()
                  .filename(
                      "[Baz-Bar]Foo - 01 - 12[1080p][Multiple Subtitle]/[Baz-Bar] Foo - 05 [1080p][Multiple Subtitle].mkv")
                  .seriesName("Foo")
                  .episode(5)
                  .build(),
              testCase().filename("[tag] Foo - 1").seriesName("Foo").episode(1).build(),
              testCase()
                  .filename(
                      "[Baz-Bar]Foo - [1080p][Multiple Subtitle]/[Baz-Bar] Foo - 05 [1080p][Multiple Subtitle].mkv")
                  .seriesName("Foo")
                  .episode(5)
                  .build(),
              testCase()
                  .filename("Season 2 /[QuietSubs] Falcon X Falcon - 136[720p].mkv")
                  .seriesName("Falcon X Falcon")
                  .episode(136)
                  .build(),
              testCase().filename("/Season 1/foo 06-06").seriesName("foo").episode(6).build(),

              // Absolute episode number
              testCase()
                  .filename("The Greenfields/The Greenfields 12.avi")
                  .seriesName("The Greenfields")
                  .episode(12)
                  .build(),
              testCase()
                  .filename("The Greenfields/The Greenfields 82.avi")
                  .seriesName("The Greenfields")
                  .episode(82)
                  .build(),
              testCase()
                  .filename("The Greenfields/The Greenfields 112.avi")
                  .seriesName("The Greenfields")
                  .episode(112)
                  .build(),
              testCase()
                  .filename("The Greenfields/The Greenfields 889.avi")
                  .seriesName("The Greenfields")
                  .episode(889)
                  .build(),
              testCase()
                  .filename("The Greenfields/The Greenfields 101.avi")
                  .seriesName("The Greenfields")
                  .episode(101)
                  .build(),

              // Version marker (anime fansub releases)
              testCase()
                  .filename("/media/[SubsMoku] Show - 01v2 (1080p).mkv")
                  .seriesName("Show")
                  .episode(1)
                  .build(),

              // Dot-separated anime bracket naming
              testCase()
                  .filename(
                      "/tv/Ironvale Tinker Fellowship/Season 1/[ZbW].Ironvale.Tinker.Fellowship.-.01.[BD.1080p.HEVC.x265.10bit.Opus.5.1][Dual.Audio].mkv")
                  .seriesName("Ironvale.Tinker.Fellowship")
                  .episode(1)
                  .build())
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
  @DisplayName("Series Name and Air Date Extraction Tests")
  class SuccessfulDateExtractionTests {

    @Builder
    record TestCase(String filename, String seriesName, LocalDate date) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should extract series name and date when path contains an air date")
    Stream<DynamicNode> shouldExtractSeriesNameAndDateWhenPathContainsAirDate() {
      return Stream.of(
              testCase()
                  .filename("/server/anything_1996.11.14")
                  .seriesName("anything")
                  .date(LocalDate.of(1996, 11, 14))
                  .build(),
              testCase()
                  .filename("/server/anything_1996-11-14")
                  .seriesName("anything")
                  .date(LocalDate.of(1996, 11, 14))
                  .build(),

              // Underscore and space delimiters (DVR recordings)
              testCase()
                  .filename("/server/anything_1996_11_14")
                  .seriesName("anything")
                  .date(LocalDate.of(1996, 11, 14))
                  .build(),
              testCase()
                  .filename("/server/anything 1996 11 14")
                  .seriesName("anything")
                  .date(LocalDate.of(1996, 11, 14))
                  .build(),

              // Reverse date (DD.MM.YYYY) with various delimiters
              testCase()
                  .filename("/server/anything_14.11.1996")
                  .seriesName("anything")
                  .date(LocalDate.of(1996, 11, 14))
                  .build(),
              testCase()
                  .filename("/server/anything_14_11_1996")
                  .seriesName("anything")
                  .date(LocalDate.of(1996, 11, 14))
                  .build(),

              // No series name (file is just a date)
              testCase()
                  .filename("/server/1996.11.14")
                  .seriesName(null)
                  .date(LocalDate.of(1996, 11, 14))
                  .build(),

              // Multi-word series name
              testCase()
                  .filename("/server/Beacon News 2018-03-24")
                  .seriesName("Beacon News")
                  .date(LocalDate.of(2018, 3, 24))
                  .build(),

              // Filenames with file extensions (real-world paths from library scanning)
              testCase()
                  .filename("/tv/Quandary!/Quandary! - 2025-11-25.mkv")
                  .seriesName("Quandary!")
                  .date(LocalDate.of(2025, 11, 25))
                  .build(),
              testCase()
                  .filename("/tv/Nightly Recap/Nightly Recap 2020.04.17.720p.mkv")
                  .seriesName("Nightly Recap")
                  .date(LocalDate.of(2020, 4, 17))
                  .build(),
              testCase()
                  .filename("/tv/anything_14.11.1996.avi")
                  .seriesName("anything")
                  .date(LocalDate.of(1996, 11, 14))
                  .build())
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
  @DisplayName("Season and Episode Extraction Tests")
  class SuccessfulSeasonEpisodeTests {

    @Builder
    record TestCase(String filename, int season, int episode) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should extract season and episode when path omits series name")
    Stream<DynamicNode> shouldExtractSeasonAndEpisodeWhenPathOmitsSeriesName() {
      return Stream.of(
              testCase().filename("Series/4-12 - The Lantern.mp4").season(4).episode(12).build(),
              testCase().filename("Series/4x12 - The Lantern").season(4).episode(12).build(),
              testCase().filename("1-12 episode name").season(1).episode(12).build(),
              testCase().filename("/server/Temp/S01E02 foo").season(1).episode(2).build(),
              testCase().filename("Season 2009/2009x02 blah.avi").season(2009).episode(2).build(),
              testCase().filename("Season 2009/S2009x02 blah.avi").season(2009).episode(2).build(),
              testCase().filename("Season 2009/S2009E02 blah.avi").season(2009).episode(2).build(),
              testCase().filename("Season 2009/S2009xE02 blah.avi").season(2009).episode(2).build(),
              testCase().filename("Season 1/01x02 blah.avi").season(1).episode(2).build(),
              testCase().filename("Season 1/S01x02 blah.avi").season(1).episode(2).build(),
              testCase().filename("Season 1/S01E02 blah.avi").season(1).episode(2).build(),
              testCase().filename("Season 1/S01xE02 blah.avi").season(1).episode(2).build(),
              testCase().filename("Season 1/02 - blah.avi").season(1).episode(2).build(),
              testCase().filename("Season 2/02 - blah 14 blah.avi").season(2).episode(2).build(),
              testCase().filename("Season 1/02 - blah-02 a.avi").season(1).episode(2).build(),
              testCase().filename("Season 2/02.avi").season(2).episode(2).build(),

              // Case sensitivity variants
              testCase().filename("/server/Temp/s01e02 foo").season(1).episode(2).build(),
              testCase().filename("/server/Temp/S01e02 foo").season(1).episode(2).build())
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
  @DisplayName("Episode Number Extraction Tests")
  class SuccessfulEpisodeTests {

    @Builder
    record TestCase(String filename, int episode) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should extract episode number when path omits season and series name")
    Stream<DynamicNode> shouldExtractEpisodeNumberWhenPathOmitsSeasonAndSeriesName() {
      return Stream.of(
              testCase()
                  .filename("The Greenfields/The Greenfields - 02 - Ep Name.avi")
                  .episode(2)
                  .build(),
              testCase().filename("The Greenfields/02.avi").episode(2).build(),
              testCase().filename("The Greenfields/02 - Ep Name.avi").episode(2).build(),
              testCase().filename("The Greenfields/02-Ep Name.avi").episode(2).build(),
              testCase().filename("The Greenfields/02.EpName.avi").episode(2).build(),
              testCase().filename("The Greenfields/The Greenfields - 02.avi").episode(2).build(),
              testCase()
                  .filename("The Greenfields/The Greenfields - 02 Ep Name.avi")
                  .episode(2)
                  .build(),
              testCase().filename("KV Circle (2013)/KV Circle - 07.mkv").episode(7).build(),

              // Absolute episode number
              testCase().filename("The Greenfields/12.avi").episode(12).build(),
              testCase().filename("The Greenfields/Foo_ep_02.avi").episode(2).build(),

              // Part number extraction
              testCase().filename("/season 1/title_part_1.avi").episode(1).build(),
              testCase().filename("/season 1/title.part.2.avi").episode(2).build(),
              testCase().filename("/season 1/title-part-3.mkv").episode(3).build(),
              testCase()
                  .filename("/Season 1/The.Quiet.Rain.Part.7.1080p.BluRay.x264-MOSSFERN.mkv")
                  .episode(7)
                  .build(),
              testCase()
                  .filename("/Season 1/Slate.Meridian.Part.4.1080p.WEBRip.x264-bQe-xpost.mkv")
                  .episode(4)
                  .build(),
              testCase().filename("/Season 1/Title.PART.5.720p.mkv").episode(5).build(),
              testCase().filename("/Season 1/Title.Pt.3.mkv").episode(3).build(),

              // E-only and Episode X patterns with full file paths
              testCase().filename("/media/Show/Season 1/Show.E01.mkv").episode(1).build(),
              testCase().filename("/media/Show/Season 1/Episode 16.mkv").episode(16).build())
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
  @DisplayName("Multi-Episode Extraction Tests")
  class SuccessfulMultiEpisodeExtractionTests {

    @Builder
    record TestCase(String filename, String seriesName, int endingEpisode) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should extract ending episode when path describes multiple episodes")
    Stream<DynamicNode> shouldExtractEndingEpisodeWhenPathDescribesMultipleEpisodes() {
      return Stream.of(
              testCase()
                  .filename("Season 02/Rudimentary - 02x03x04x15 - Ep Name.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2/Rudimentary - 02x03 - 02x04 - 02x15 - Ep Name.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2/02x03 - 02x04 - 02x15 - Ep Name.mp4")
                  .seriesName(null)
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2/02x03-04-15 - Ep Name.mp4")
                  .seriesName(null)
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 1/S01E23-E24-E26 - The Lantern.mp4")
                  .seriesName(null)
                  .endingEpisode(26)
                  .build(),
              testCase()
                  .filename("Season 02/02x03-E15 - Ep Name.mp4")
                  .seriesName(null)
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2/Rudimentary - 02x03-04-15 - Ep Name.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 1/Rudimentary - S01E23-E24-E26 - The Lantern.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(26)
                  .build(),
              testCase()
                  .filename("Season 02/Rudimentary - 02x03-E15 - Ep Name.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 02/02x03 - x04 - x15 - Ep Name.mp4")
                  .seriesName(null)
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 02/Rudimentary - 02x03 - x04 - x15 - Ep Name.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 02/02x03x04x15 - Ep Name.mp4")
                  .seriesName(null)
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2009/Rudimentary - 2009x03x04x15 - Ep Name.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2009/Rudimentary - 2009x03 - 2009x04 - 2009x15 - Ep Name.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2009/2009x03 - 2009x04 - 2009x15 - Ep Name.mp4")
                  .seriesName(null)
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2009/2009x03-04-15 - Ep Name.mp4")
                  .seriesName(null)
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2009/S2009E23-E24-E26 - The Lantern.mp4")
                  .seriesName(null)
                  .endingEpisode(26)
                  .build(),
              testCase()
                  .filename("Season 2009/2009x03-E15 - Ep Name.mp4")
                  .seriesName(null)
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2009/Rudimentary - 2009x03-04-15 - Ep Name.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2009/Rudimentary - S2009E23-E24-E26 - The Lantern.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(26)
                  .build(),
              testCase()
                  .filename("Season 2009/Rudimentary - 2009x03-E15 - Ep Name.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2009/2009x03 - x04 - x15 - Ep Name.mp4")
                  .seriesName(null)
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2009/Rudimentary - 2009x03 - x04 - x15 - Ep Name.mp4")
                  .seriesName("Rudimentary")
                  .endingEpisode(15)
                  .build(),
              testCase()
                  .filename("Season 2009/02x03x04x15 - Ep Name.mp4")
                  .seriesName(null)
                  .endingEpisode(15)
                  .build(),
              testCase().filename("/Season 1/foo 03-06").seriesName("foo").endingEpisode(6).build(),
              testCase()
                  .filename("Season 1/02-03 - blah.avi")
                  .seriesName(null)
                  .endingEpisode(3)
                  .build(),
              testCase()
                  .filename("Season 2/02-04 - blah 14 blah.avi")
                  .seriesName(null)
                  .endingEpisode(4)
                  .build(),
              testCase()
                  .filename("Season 1/02-05 - blah-02 a.avi")
                  .seriesName(null)
                  .endingEpisode(5)
                  .build(),
              testCase().filename("Season 2/02-04.avi").seriesName(null).endingEpisode(4).build(),
              testCase()
                  .filename("Season 1/WAYFARING_s01e01-e04")
                  .seriesName("WAYFARING")
                  .endingEpisode(4)
                  .build())
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
    @DisplayName("Should not create phantom season when episode title contains trailing year")
    void shouldNotCreatePhantomSeasonWhenEpisodeTitleContainsTrailingYear() {
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
    @DisplayName("Should ignore trailing year when episode number has no season prefix")
    void shouldIgnoreTrailingYearWhenEpisodeNumberHasNoSeasonPrefix() {
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
