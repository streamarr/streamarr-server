package com.streamarr.server.services.parsers.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
@DisplayName("Default Video File Metadata Parsing Tests")
class DefaultVideoFileMetadataParserTest {

  private final DefaultVideoFileMetadataParser defaultVideoFileMetadataParser =
      new DefaultVideoFileMetadataParser();

  @Test
  @DisplayName("Should parse promptly when a dash run is not trailing")
  void shouldParsePromptlyWhenDashRunIsNotTrailing() {
    var filename = "A" + "-".repeat(20_000) + "Z";

    assertTimeoutPreemptively(
        Duration.ofMillis(500),
        () ->
            assertThat(defaultVideoFileMetadataParser.parse(filename).orElseThrow().title())
                .isEqualTo(filename));
  }

  @Test
  @DisplayName("Should parse promptly when a year follows an unsupported separator")
  void shouldParsePromptlyWhenYearFollowsUnsupportedSeparator() {
    var filename = "A" + " ".repeat(30_000) + ",2010";

    assertTimeoutPreemptively(
        Duration.ofMillis(500),
        () ->
            assertThat(defaultVideoFileMetadataParser.parse(filename).orElseThrow().title())
                .isEqualTo(filename));
  }

  @Test
  @DisplayName("Should return empty when a year has only whitespace before it")
  void shouldReturnEmptyWhenYearHasOnlyWhitespaceBeforeIt() {
    assertThat(defaultVideoFileMetadataParser.parse("  2012")).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when title cleanup removes the entire filename")
  void shouldReturnEmptyWhenTitleCleanupRemovesEntireFilename() {
    assertThat(defaultVideoFileMetadataParser.parse("---")).isEmpty();
  }

  @Test
  @DisplayName(
      "Should use supported separator fallback when preferred separator follows invalid title ending")
  void shouldUseSupportedSeparatorFallbackWhenPreferredSeparatorFollowsInvalidTitleEnding() {
    var result = defaultVideoFileMetadataParser.parse("Movie-.2012").orElseThrow();

    assertThat(result.title()).isEqualTo("Movie");
    assertThat(result.year()).isEqualTo("2012");
  }

  @Test
  @DisplayName("Should preserve filename as title when year has no valid title prefix")
  void shouldPreserveFilenameAsTitleWhenYearHasNoValidTitlePrefix() {
    var result = defaultVideoFileMetadataParser.parse("--- 2012").orElseThrow();

    assertThat(result.title()).isEqualTo("--- 2012");
    assertThat(result.year()).isNull();
  }

  @Nested
  @DisplayName("Title and Year Extraction Tests")
  class SuccessfulTitleAndYearExtractionTests {

    @Builder
    record TestCase(String title, String year, String filename) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should extract title and year when filename contains release metadata")
    Stream<DynamicNode> shouldExtractTitleAndYearWhenFilenameContainsReleaseMetadata() {
      return Stream.of(
              testCase().title("Garnet Vale").year("2002").filename("Garnet Vale 2002").build(),
              testCase().title("Garnet Vale").year("2002").filename("Garnet Vale (2002)").build(),
              testCase().title("Veldane").year("1988").filename("Veldane 1988").build(),
              testCase().title("Veldane").year("1988").filename("Veldane (1988)").build(),
              testCase().title("$").year("1973").filename("$ 1973").build(),
              testCase().title("$").year("1973").filename("$ (1973)").build(),
              testCase()
                  .title("Tricky Movie Name 2001")
                  .year("2012")
                  .filename("Tricky Movie Name 2001 2012")
                  .build(),
              testCase()
                  .title("Tricky Movie Name 2001")
                  .year("2012")
                  .filename("Tricky Movie Name 2001 (2012)")
                  .build(),
              testCase()
                  .title("Marsh Warden")
                  .year("2018")
                  .filename("Marsh Warden [Multi-Subs] 2018")
                  .build(),
              testCase()
                  .title("Marsh Warden")
                  .year("2018")
                  .filename("Marsh Warden [Multi-Subs] [2018]")
                  .build(),
              testCase()
                  .title("Marsh Warden")
                  .year("2018")
                  .filename("Marsh Warden [Multi-Subs] (2018)")
                  .build(),
              testCase()
                  .title("A Breezy Picture")
                  .year("1995")
                  .filename("[Multi-Subs] A Breezy Picture (1995)")
                  .build(),
              testCase()
                  .title("The.Improbable.Mass.of.Gentle.Static")
                  .year("2022")
                  .filename("The.Improbable.Mass.of.Gentle.Static.2022.HDR.2160p.WEB.H265")
                  .build(),
              testCase()
                  .title("The Movie Title")
                  .year("2010")
                  .filename(
                      "The Movie Title (2010) Ultimate Extended Edition [imdb-tt5203941][IMAX HYBRID][Bluray-1080p Proper][3D][DV HDR10][DTS 5.1][x264]")
                  .build(),
              testCase()
                  .title("Home Movie 2012-12-12")
                  .year("2012")
                  .filename("Home Movie 2012-12-12 2012")
                  .build(),
              testCase()
                  .title("3 nights to sail")
                  .year("2014")
                  .filename("3 nights to sail (2014)")
                  .build(),
              testCase()
                  .title("3.Nights.to.Sail")
                  .year("2014")
                  .filename("3.Nights.to.Sail.2014.720p.BluRay.x264.PELT")
                  .build(),
              testCase()
                  .title("Fern Warden")
                  .year("1988")
                  .filename("Fern Warden 1988 REMASTERED 1080p BluRay x264 AAC - Quill")
                  .build(),
              testCase()
                  .title("A Movie")
                  .year("1996")
                  .filename("A Movie (1996) - AnotherTitle 2019.mp4")
                  .build(),
              testCase()
                  .title("Meridian Glide")
                  .year("2016")
                  .filename("Meridian Glide - 2016 - WEBDL-1080p - x264 AC3")
                  .build(),
              testCase().title("No Space").year("2000").filename("No Space(2000)").build(),
              testCase().title("Mr. Bramble").year("2019").filename("Mr. Bramble 2019").build(),
              testCase().title("512").year("2006").filename("512 (2006)").build(),
              testCase().title("512 2").year("2006").filename("512 2 (2006)").build(),
              testCase().title("512 - 2").year("2006").filename("512 - 2 (2006)").build(),
              testCase()
                  .title("[HUM]")
                  .year("2007")
                  .filename("[HUM] (2007) - [REMUX-1080p][AC3 5.1].mkv")
                  .build(),
              testCase()
                  .title("Glint")
                  .year("2022")
                  .filename("Glint (2022) [WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv")
                  .build(),
              testCase()
                  .title("3 Anchors")
                  .year("2009")
                  .filename("3 Anchors - (2009) - [Bluray-1080p][DTS-HD MA 5.1].mkv")
                  .build())
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
  @DisplayName("Adversarial Metadata Extraction Tests")
  class AdversarialExtractionTests {

    @Builder
    record TestCase(String title, String year, String filename) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should extract metadata when filename contains adversarial numbering or scripts")
    Stream<DynamicNode> shouldExtractMetadataWhenFilenameContainsAdversarialNumberingOrScripts() {
      return Stream.of(
              testCase()
                  .title("Iron Orchard 1974")
                  .year("2011")
                  .filename("Iron Orchard 1974 (2011)")
                  .build(),
              testCase().title("2049").year("2019").filename("2049 (2019)").build(),
              testCase()
                  .title("480 Velvet Antenna")
                  .year(null)
                  .filename("480 Velvet Antenna")
                  .build(),
              testCase().title("銅の子午線　特別版").year("2016").filename("銅の子午線　特別版 (2016)").build(),
              testCase().title("달빛 수리공").year("2021").filename("[MOKSA] 달빛 수리공 (2021)").build(),
              testCase()
                  .title("Медный меридиан")
                  .year("1987")
                  .filename("Медный меридиан (1987)")
                  .build(),
              testCase()
                  .title("Paper Comet ½")
                  .year("2003")
                  .filename("Paper Comet ½ (2003)")
                  .build(),
              testCase()
                  .title("Paper Comet")
                  .year(null)
                  .filename("Paper Comet [1920x1080]")
                  .build(),
              testCase()
                  .title("Signal Garden")
                  .year("2019")
                  .filename("Signal Garden 2019 x264")
                  .build(),
              testCase()
                  .title("Quiet Alloy")
                  .year("2014")
                  .filename("Quiet Alloy – 2014 – WEBDL-1080p")
                  .build(),
              // U+0301 combining acute: the decomposed (NFD) form must survive unnormalized.
              testCase()
                  .title("Clémentine Harvest")
                  .year("2015")
                  .filename("Clémentine Harvest (2015)")
                  .build())
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
  @DisplayName("Unsuccessful Metadata Extraction Tests")
  class UnsuccessfulExtractionTests {

    @Builder
    record TestCase(String name, String input) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should return empty when filename has no metadata")
    Stream<DynamicNode> shouldReturnEmptyWhenFilenameHasNoMetadata() {
      return Stream.of(
              testCase().name("when given null input").input(null).build(),
              testCase().name("when given empty input").input("").build(),
              testCase().name("when given blank input").input(" ").build())
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
