package com.streamarr.server.services.parsers.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

@Tag("UnitTest")
@DisplayName("Default Video File Metadata Parsing Tests")
class DefaultVideoFileMetadataParserTest {

  private final DefaultVideoFileMetadataParser defaultVideoFileMetadataParser =
      new DefaultVideoFileMetadataParser();

  @Nested
  @DisplayName("Should successfully extract both title and year from filename")
  class SuccessfulTitleAndYearExtractionTests {

    record TestCase(String title, String year, String filename) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase("Garnet Vale", "2002", "Garnet Vale 2002"),
              new TestCase("Garnet Vale", "2002", "Garnet Vale (2002)"),
              new TestCase("Veldane", "1988", "Veldane 1988"),
              new TestCase("Veldane", "1988", "Veldane (1988)"),
              new TestCase("$", "1973", "$ 1973"),
              new TestCase("$", "1973", "$ (1973)"),
              new TestCase("Tricky Movie Name 2001", "2012", "Tricky Movie Name 2001 2012"),
              new TestCase("Tricky Movie Name 2001", "2012", "Tricky Movie Name 2001 (2012)"),
              new TestCase("Marsh Warden", "2018", "Marsh Warden [Multi-Subs] 2018"),
              new TestCase("Marsh Warden", "2018", "Marsh Warden [Multi-Subs] [2018]"),
              new TestCase("Marsh Warden", "2018", "Marsh Warden [Multi-Subs] (2018)"),
              new TestCase("A Breezy Picture", "1995", "[Multi-Subs] A Breezy Picture (1995)"),
              new TestCase(
                  "The.Improbable.Mass.of.Gentle.Static",
                  "2022",
                  "The.Improbable.Mass.of.Gentle.Static.2022.HDR.2160p.WEB.H265"),
              new TestCase(
                  "The Movie Title",
                  "2010",
                  "The Movie Title (2010) Ultimate Extended Edition [imdb-tt5203941][IMAX HYBRID][Bluray-1080p Proper][3D][DV HDR10][DTS 5.1][x264]"),
              new TestCase("Home Movie 2012-12-12", "2012", "Home Movie 2012-12-12 2012"),
              new TestCase("3 nights to sail", "2014", "3 nights to sail (2014)"),
              new TestCase(
                  "3.Nights.to.Sail", "2014", "3.Nights.to.Sail.2014.720p.BluRay.x264.PELT"),
              new TestCase(
                  "Fern Warden",
                  "1988",
                  "Fern Warden 1988 REMASTERED 1080p BluRay x264 AAC - Quill"),
              new TestCase("A Movie", "1996", "A Movie (1996) - AnotherTitle 2019.mp4"),
              new TestCase(
                  "Meridian Glide", "2016", "Meridian Glide - 2016 - WEBDL-1080p - x264 AC3"),
              new TestCase("No Space", "2000", "No Space(2000)"),
              new TestCase("Mr. Bramble", "2019", "Mr. Bramble 2019"),
              new TestCase("512", "2006", "512 (2006)"),
              new TestCase("512 2", "2006", "512 2 (2006)"),
              new TestCase("512 - 2", "2006", "512 - 2 (2006)"),
              new TestCase("[HUM]", "2007", "[HUM] (2007) - [REMUX-1080p][AC3 5.1].mkv"),
              new TestCase("Glint", "2022", "Glint (2022) [WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv"),
              new TestCase(
                  "3 Anchors", "2009", "3 Anchors - (2009) - [Bluray-1080p][DTS-HD MA 5.1].mkv"))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result = defaultVideoFileMetadataParser.parse(testCase.filename());

                        assertThat(result.orElseThrow().title()).isEqualTo(testCase.title());
                        assertThat(result.orElseThrow().year()).isEqualTo(testCase.year());
                      }));
    }
  }

  @Nested
  @DisplayName("Should successfully extract a title from filename")
  class SuccessfulTitleExtractionTests {

    record TestCase(String title, String filename) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase("a", " a "),
              new TestCase("$", " $ "),
              new TestCase("2002", " 2002 "),
              new TestCase("Just a Title", "Just a Title"),
              // new TestCase("Title", "(2012) Title"),
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
              new TestCase("Known.Exclusion", "Known.Exclusion.4K.UltraHD.HDR.BDrip-HDC"))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result = defaultVideoFileMetadataParser.parse(testCase.filename());

                        assertThat(result.orElseThrow().title()).isEqualTo(testCase.title());
                        assertThat(result.orElseThrow().year()).isNull();
                      }));
    }
  }

  @Nested
  @DisplayName("Should extract metadata when filename contains adversarial numbering or scripts")
  class AdversarialExtractionTests {

    record TestCase(String title, String year, String filename) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase("Iron Orchard 1974", "2011", "Iron Orchard 1974 (2011)"),
              new TestCase("2049", "2019", "2049 (2019)"),
              new TestCase("480 Velvet Antenna", null, "480 Velvet Antenna"),
              new TestCase("銅の子午線　特別版", "2016", "銅の子午線　特別版 (2016)"),
              new TestCase("달빛 수리공", "2021", "[MOKSA] 달빛 수리공 (2021)"),
              new TestCase("Медный меридиан", "1987", "Медный меридиан (1987)"),
              new TestCase("Paper Comet ½", "2003", "Paper Comet ½ (2003)"),
              // U+0301 combining acute: the decomposed (NFD) form must survive unnormalized.
              new TestCase("Clémentine Harvest", "2015", "Clémentine Harvest (2015)"))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result = defaultVideoFileMetadataParser.parse(testCase.filename());

                        assertThat(result.orElseThrow().title()).isEqualTo(testCase.title());
                        assertThat(result.orElseThrow().year()).isEqualTo(testCase.year());
                      }));
    }
  }

  @Nested
  @DisplayName("Should return empty optional")
  class UnsuccessfulExtractionTests {

    record TestCase(String name, String input) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase("when given null input", null),
              new TestCase("when given empty input", ""),
              new TestCase("when given blank input", " "))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.name(),
                      () -> {
                        var result = defaultVideoFileMetadataParser.parse(testCase.input());

                        assertTrue(result.isEmpty());
                      }));
    }
  }
}
