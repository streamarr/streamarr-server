package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Client Capability Declaration Tests")
class ClientCapabilityDeclarationTest {

  private static final Map<
          String, UnaryOperator<ClientCapabilityDeclaration.ClientCapabilityDeclarationBuilder>>
      REQUIRED_DECLARATION_LISTS =
          Map.of(
              "video", builder -> builder.video(List.of(h264EntryBuilder().build())),
              "audio", builder -> builder.audio(List.of(aacEntryBuilder().build())));

  private static final Map<String, UnaryOperator<VideoCapabilityEntry.VideoCapabilityEntryBuilder>>
      REQUIRED_VIDEO_ENTRY_VALUES =
          Map.of(
              "codecFamily", builder -> builder.codecFamily(ProbedCodecFamily.H264),
              "codecProfiles",
                  builder -> builder.codecProfiles(h264EntryBuilder().build().codecProfiles()),
              "compatibleBases", builder -> builder.compatibleBases(List.of(CompatibleBase.SDR)),
              "dolbyVisionProfiles", builder -> builder.dolbyVisionProfiles(List.of()));

  @ParameterizedTest
  @MethodSource("requiredDeclarationLists")
  @DisplayName("Should refuse a declaration when it leaves out its video or audio entries")
  void shouldRefuseADeclarationWhenItLeavesOutItsVideoOrAudioEntries(String omitted) {
    var declaration = ClientCapabilityDeclaration.builder();
    REQUIRED_DECLARATION_LISTS.entrySet().stream()
        .filter(required -> !required.getKey().equals(omitted))
        .forEach(required -> required.getValue().apply(declaration));

    assertThatThrownBy(declaration::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining(omitted);
  }

  @ParameterizedTest
  @MethodSource("requiredVideoEntryValues")
  @DisplayName("Should refuse a video capability entry when it leaves out a required value")
  void shouldRefuseAVideoCapabilityEntryWhenItLeavesOutARequiredValue(String omitted) {
    var entry = VideoCapabilityEntry.builder().maxBitDepth(8).maxWidth(1920).maxHeight(1080);
    REQUIRED_VIDEO_ENTRY_VALUES.entrySet().stream()
        .filter(required -> !required.getKey().equals(omitted))
        .forEach(required -> required.getValue().apply(entry));

    assertThatThrownBy(entry::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining(omitted);
  }

  @Test
  @DisplayName("Should keep its capability entries when the caller changes its lists afterwards")
  void shouldKeepItsCapabilityEntriesWhenTheCallerChangesItsListsAfterwards() {
    var video = new ArrayList<>(List.of(h264EntryBuilder().build()));
    var audio = new ArrayList<>(List.of(aacEntryBuilder().build()));

    var declaration = ClientCapabilityDeclaration.builder().video(video).audio(audio).build();
    video.clear();
    audio.clear();

    assertThat(declaration.video()).hasSize(1);
    assertThat(declaration.audio()).hasSize(1);
  }

  @Test
  @DisplayName("Should keep an entry's lists when the caller changes them afterwards")
  void shouldKeepAnEntrysListsWhenTheCallerChangesThemAfterwards() {
    var entry = h264EntryBuilder().build();
    var profiles = new ArrayList<>(entry.codecProfiles());
    var bases = new ArrayList<>(entry.compatibleBases());
    var dolbyVision =
        new ArrayList<>(
            List.of(
                DolbyVisionProfileCapability.builder()
                    .profile(DolbyVisionProfile.PROFILE_8_1)
                    .maxLevel(6)
                    .build()));

    var copy =
        h264EntryBuilder()
            .codecProfiles(profiles)
            .compatibleBases(bases)
            .dolbyVisionProfiles(dolbyVision)
            .build();
    profiles.clear();
    bases.clear();
    dolbyVision.clear();

    assertThat(copy.codecProfiles()).isEqualTo(entry.codecProfiles());
    assertThat(copy.compatibleBases()).containsExactly(CompatibleBase.SDR);
    assertThat(copy.dolbyVisionProfiles()).hasSize(1);
  }

  @ParameterizedTest
  @EnumSource(
      value = CompatibleBase.class,
      names = {"NONE", "UNSPECIFIED"})
  @DisplayName("Should refuse a compatible base when no player can declare it")
  void shouldRefuseACompatibleBaseWhenNoPlayerCanDeclareIt(CompatibleBase base) {
    var entry = h264EntryBuilder().compatibleBases(List.of(CompatibleBase.SDR, base));

    assertThatThrownBy(entry::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should refuse a video capability entry when it names no known codec family")
  void shouldRefuseAVideoCapabilityEntryWhenItNamesNoKnownCodecFamily() {
    var entry = h264EntryBuilder().codecFamily(ProbedCodecFamily.OTHER);

    assertThatThrownBy(entry::build).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(DolbyVisionProfile.class)
  @DisplayName("Should carry HEVC or AV1 when a Dolby Vision profile names its codec family")
  void shouldCarryHevcOrAv1WhenADolbyVisionProfileNamesItsCodecFamily(DolbyVisionProfile profile) {
    var expected =
        profile.name().startsWith("PROFILE_10") ? ProbedCodecFamily.AV1 : ProbedCodecFamily.HEVC;

    assertThat(profile.codecFamily()).isEqualTo(expected);
  }

  @Test
  @DisplayName("Should name each audio codec by its probed name when a decision compares codecs")
  void shouldNameEachAudioCodecByItsProbedNameWhenADecisionComparesCodecs() {
    assertThat(List.of(AudioCodec.values()).stream().map(AudioCodec::probedName))
        .containsExactly("aac", "ac3", "eac3", "opus", "flac", "alac");
  }

  static Stream<String> requiredDeclarationLists() {
    return REQUIRED_DECLARATION_LISTS.keySet().stream().sorted();
  }

  static Stream<String> requiredVideoEntryValues() {
    return REQUIRED_VIDEO_ENTRY_VALUES.keySet().stream().sorted();
  }
}
