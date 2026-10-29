package com.streamarr.server.services.streaming;

import static com.streamarr.server.domain.streaming.DolbyVisionProfile.PROFILE_10_0;
import static com.streamarr.server.domain.streaming.DolbyVisionProfile.PROFILE_10_1;
import static com.streamarr.server.domain.streaming.DolbyVisionProfile.PROFILE_5;
import static com.streamarr.server.domain.streaming.DolbyVisionProfile.PROFILE_8_1;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.startPlaybackBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.AudioCapabilityEntry;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.AudioChannelsNotPositive;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.AudioEntriesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecLevelNotPositive;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecProfileOutsideCodecFamily;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecProfilesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecTierMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecTierNotApplicable;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CompatibleBasesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.DolbyVisionLevelNotPositive;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.DolbyVisionProfileOutsideCodecFamily;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.HlsAacEntryMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyAudioEntries;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyCodecProfiles;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyCompatibleBases;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyDolbyVisionProfiles;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyVideoEntries;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.VideoEntriesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.VideoEntryMaximum;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.VideoMaximumInvalid;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Playback Session Capability Declaration Tests")
class PlaybackSessionCapabilityDeclarationTest {

  // A list one value longer than a declaration may hold.
  private static final int OVERLONG = 33;

  private final FakeCreatingStreamingService streams = new FakeCreatingStreamingService();
  private final PlaybackSessionService service = playbackServiceBuilder(streams).build();

  @Test
  @DisplayName("Should start playback when every capability entry is valid")
  void shouldStartPlaybackWhenEveryCapabilityEntryIsValid() {
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().build(),
                    hevcEntryBuilder()
                        .dolbyVisionProfiles(List.of(dolbyVision(PROFILE_8_1)))
                        .build(),
                    av1EntryBuilder()
                        .dolbyVisionProfiles(List.of(dolbyVision(PROFILE_10_1)))
                        .build()))
            .build();

    var outcome = start(declaration);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(streams.getAllSessions()).hasSize(1);
  }

  @Test
  @DisplayName("Should refuse both lists when the declaration has no capability entry")
  void shouldRefuseBothListsWhenTheDeclarationHasNoCapabilityEntry() {
    var declaration =
        ClientCapabilityDeclaration.builder().video(List.of()).audio(List.of()).build();

    assertThat(start(declaration))
        .isEqualTo(
            Outcome.rejected(
                List.of(
                    new VideoEntriesMissing(),
                    new AudioEntriesMissing(),
                    new HlsAacEntryMissing())));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("audioWithoutHlsAac")
  @DisplayName("Should refuse the declaration when no AAC entry applies to HLS")
  void shouldRefuseTheDeclarationWhenNoAacEntryAppliesToHls(
      String scenario, List<AudioCapabilityEntry> audio) {
    var declaration = defaultDeclarationBuilder().audio(audio).build();

    assertThat(start(declaration)).isEqualTo(Outcome.rejected(new HlsAacEntryMissing()));
  }

  static Stream<Arguments> audioWithoutHlsAac() {
    return Stream.of(
        Arguments.of("Opus only", List.of(audioEntryBuilder(AudioCodec.OPUS).build())),
        Arguments.of("AAC for direct play only", List.of(aacEntryBuilder().hls(false).build())));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -2})
  @DisplayName("Should refuse every maximum of an entry when the maximums are not positive")
  void shouldRefuseEveryMaximumOfAnEntryWhenTheMaximumsAreNotPositive(int maximum) {
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().build(),
                    h264EntryBuilder()
                        .maxBitDepth(maximum)
                        .maxWidth(maximum)
                        .maxHeight(maximum)
                        .maxFrameRate(maximum)
                        .build()))
            .audio(
                List.of(aacEntryBuilder().build(), aacEntryBuilder().maxChannels(maximum).build()))
            .build();

    assertThat(start(declaration))
        .isEqualTo(
            Outcome.rejected(
                List.of(
                    new VideoMaximumInvalid(1, VideoEntryMaximum.BIT_DEPTH),
                    new VideoMaximumInvalid(1, VideoEntryMaximum.WIDTH),
                    new VideoMaximumInvalid(1, VideoEntryMaximum.HEIGHT),
                    new VideoMaximumInvalid(1, VideoEntryMaximum.FRAME_RATE),
                    new AudioChannelsNotPositive(1))));
  }

  @Test
  @DisplayName("Should refuse a video entry when it lists no codec profile or compatible base")
  void shouldRefuseAVideoEntryWhenItListsNoCodecProfileOrCompatibleBase() {
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().codecProfiles(List.of()).compatibleBases(List.of()).build()))
            .build();

    assertThat(start(declaration))
        .isEqualTo(
            Outcome.rejected(List.of(new CodecProfilesMissing(0), new CompatibleBasesMissing(0))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("entriesForDolbyVisionWithoutACompatibleBase")
  @DisplayName(
      "Should start playback when a video entry lists no compatible base for Dolby Vision without one")
  void shouldStartPlaybackWhenAVideoEntryListsNoCompatibleBaseForDolbyVisionWithoutOne(
      String scenario, VideoCapabilityEntry entry) {
    var declaration =
        defaultDeclarationBuilder().video(List.of(h264EntryBuilder().build(), entry)).build();

    var outcome = start(declaration);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(streams.getAllSessions()).hasSize(1);
  }

  static Stream<Arguments> entriesForDolbyVisionWithoutACompatibleBase() {
    return Stream.of(
        Arguments.of(
            "HEVC with Dolby Vision profile 5",
            hevcEntryBuilder()
                .compatibleBases(List.of())
                .dolbyVisionProfiles(List.of(dolbyVision(PROFILE_5)))
                .build()),
        Arguments.of(
            "AV1 with Dolby Vision profile 10.0",
            av1EntryBuilder()
                .compatibleBases(List.of())
                .dolbyVisionProfiles(List.of(dolbyVision(PROFILE_10_0)))
                .build()));
  }

  @Test
  @DisplayName(
      "Should refuse a video entry without a compatible base when each Dolby Vision profile has one")
  void shouldRefuseAVideoEntryWithoutACompatibleBaseWhenEachDolbyVisionProfileHasOne() {
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    hevcEntryBuilder()
                        .compatibleBases(List.of())
                        .dolbyVisionProfiles(List.of(dolbyVision(PROFILE_8_1)))
                        .build()))
            .build();

    assertThat(start(declaration)).isEqualTo(Outcome.rejected(new CompatibleBasesMissing(0)));
  }

  @Test
  @DisplayName("Should refuse each codec profile fault when profiles break their entry's rules")
  void shouldRefuseEachCodecProfileFaultWhenProfilesBreakTheirEntrysRules() {
    var entry =
        hevcEntryBuilder()
            .codecProfiles(
                List.of(
                    codecProfile(VideoCodecProfile.HEVC_MAIN, 51, Optional.of(VideoCodecTier.MAIN)),
                    codecProfile(VideoCodecProfile.H264_HIGH, 0, Optional.of(VideoCodecTier.HIGH)),
                    codecProfile(VideoCodecProfile.HEVC_MAIN_10, -1, Optional.empty())))
            .build();
    var declaration = defaultDeclarationBuilder().video(List.of(entry)).build();

    assertThat(start(declaration))
        .isEqualTo(
            Outcome.rejected(
                List.of(
                    new CodecProfileOutsideCodecFamily(0, 1),
                    new CodecLevelNotPositive(0, 1),
                    new CodecTierNotApplicable(0, 1),
                    new CodecLevelNotPositive(0, 2),
                    new CodecTierMissing(0, 2))));
  }

  @Test
  @DisplayName("Should refuse an AV1 codec profile when it names no maximum tier")
  void shouldRefuseAnAv1CodecProfileWhenItNamesNoMaximumTier() {
    var entry =
        av1EntryBuilder()
            .codecProfiles(List.of(codecProfile(VideoCodecProfile.AV1_MAIN, 51, Optional.empty())))
            .build();

    assertThat(start(defaultDeclarationBuilder().video(List.of(entry)).build()))
        .isEqualTo(Outcome.rejected(new CodecTierMissing(0, 0)));
  }

  @Test
  @DisplayName("Should refuse a Dolby Vision profile when another codec family carries it")
  void shouldRefuseADolbyVisionProfileWhenAnotherCodecFamilyCarriesIt() {
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    hevcEntryBuilder()
                        .dolbyVisionProfiles(
                            List.of(
                                dolbyVision(PROFILE_5),
                                dolbyVision(PROFILE_10_0),
                                DolbyVisionProfileCapability.builder()
                                    .profile(DolbyVisionProfile.PROFILE_8_4)
                                    .maxLevel(0)
                                    .build()))
                        .build(),
                    av1EntryBuilder()
                        .dolbyVisionProfiles(List.of(dolbyVision(PROFILE_8_1)))
                        .build(),
                    h264EntryBuilder()
                        .dolbyVisionProfiles(List.of(dolbyVision(PROFILE_5)))
                        .build()))
            .build();

    assertThat(start(declaration))
        .isEqualTo(
            Outcome.rejected(
                List.of(
                    new DolbyVisionProfileOutsideCodecFamily(0, 1),
                    new DolbyVisionLevelNotPositive(0, 2),
                    new DolbyVisionProfileOutsideCodecFamily(1, 0),
                    new DolbyVisionProfileOutsideCodecFamily(2, 0))));
  }

  @Test
  @DisplayName("Should refuse each overlong list without checking its values")
  void shouldRefuseEachOverlongListWithoutCheckingItsValues() {
    var overlongEntry =
        h264EntryBuilder()
            .codecProfiles(
                Collections.nCopies(
                    OVERLONG, codecProfile(VideoCodecProfile.H264_HIGH, 0, Optional.empty())))
            .compatibleBases(Collections.nCopies(OVERLONG, CompatibleBase.SDR))
            .dolbyVisionProfiles(Collections.nCopies(OVERLONG, dolbyVision(PROFILE_8_1)))
            .build();
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(overlongEntry))
            .audio(Collections.nCopies(OVERLONG, aacEntryBuilder().maxChannels(0).build()))
            .build();

    assertThat(start(declaration))
        .isEqualTo(
            Outcome.rejected(
                List.of(
                    new TooManyCodecProfiles(0, 32),
                    new TooManyCompatibleBases(0, 32),
                    new TooManyDolbyVisionProfiles(0, 32),
                    new TooManyAudioEntries(32))));
  }

  @Test
  @DisplayName("Should refuse the video list when it holds more entries than the server accepts")
  void shouldRefuseTheVideoListWhenItHoldsMoreEntriesThanTheServerAccepts() {
    var declaration =
        defaultDeclarationBuilder()
            .video(Collections.nCopies(OVERLONG, h264EntryBuilder().maxWidth(0).build()))
            .build();

    assertThat(start(declaration)).isEqualTo(Outcome.rejected(new TooManyVideoEntries(32)));
  }

  @Test
  @DisplayName("Should accept as many entries as the server allows when each list is full")
  void shouldAcceptAsManyEntriesAsTheServerAllowsWhenEachListIsFull() {
    var declaration =
        defaultDeclarationBuilder()
            .video(Collections.nCopies(32, h264EntryBuilder().build()))
            .audio(Collections.nCopies(32, aacEntryBuilder().build()))
            .build();

    assertThat(start(declaration)).isInstanceOf(Outcome.Accepted.class);
  }

  private Outcome<PlaybackSessionSnapshot, StartPlaybackSessionRejection> start(
      ClientCapabilityDeclaration declaration) {
    return service.start(startPlaybackBuilder().capabilityDeclaration(declaration).build());
  }

  private static DolbyVisionProfileCapability dolbyVision(DolbyVisionProfile profile) {
    return DolbyVisionProfileCapability.builder().profile(profile).maxLevel(6).build();
  }

  private static VideoCodecProfileCapability codecProfile(
      VideoCodecProfile profile, int maxLevel, Optional<VideoCodecTier> maxTier) {
    return VideoCodecProfileCapability.builder()
        .profile(profile)
        .maxLevel(maxLevel)
        .maxTier(maxTier)
        .build();
  }
}
