package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.profileScopedBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.remuxDecisionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionEnhancementLayer;
import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncodeFormat;
import com.streamarr.server.domain.streaming.NetworkRoute;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Playback Video Details Tests")
class PlaybackVideoDetailsTest {
  private final FakeCreatingStreamingService streams = new FakeCreatingStreamingService();
  private final PlaybackSessionService service =
      PlaybackSessionService.builder()
          .streamingService(streams)
          .authorityGate(new FakePlaybackAuthorityGate())
          .eventPublisher(new CapturingEventPublisher())
          .executor(Runnable::run)
          .clock(new MutableClock())
          .maximumFormatAttempts(2)
          .build();

  @Test
  @DisplayName("Should keep profile seven as the source when the copy delivers profile eight one")
  void shouldKeepProfileSevenAsTheSourceWhenTheCopyDeliversProfileEightOne() {
    var facts = profileSevenFacts();
    var rewrites = List.of(BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1);
    streams.shapeSessions(
        session ->
            session
                .mediaProbe(defaultProbeBuilder().videoPlaybackFacts(Optional.of(facts)).build())
                .transcodeDecision(
                    remuxDecisionBuilder()
                        .videoSource(Optional.of(facts))
                        .videoBitstreamRewrites(rewrites)
                        .build()));

    var video = start().video();

    assertThat(video.delivery()).isEqualTo(PlaybackDelivery.COPY);
    var source = video.source().details().orElseThrow();
    var output = video.output().details().orElseThrow();
    assertThat(source.dynamicRange()).isEqualTo(facts.dynamicRange());
    assertThat(source.bitDepth()).hasValue(10);
    assertThat(source.bitstreamRewrites()).isEmpty();
    assertThat(output.dynamicRange().dolbyVision())
        .isInstanceOfSatisfying(
            DolbyVision.Present.class,
            dolby -> {
              assertThat(dolby.declared().orElseThrow().profile()).hasValue(8);
              assertThat(dolby.declared().orElseThrow().baseLayerCompatibilityId()).hasValue(1);
              assertThat(dolby.declared().orElseThrow().level()).hasValue(6);
              assertThat(dolby.enhancementLayer()).isEqualTo(DolbyVisionEnhancementLayer.NONE);
            });
    assertThat(output.bitstreamRewrites()).isEqualTo(rewrites);
    assertThat(output.masteringDisplay()).isEqualTo(facts.masteringDisplay());
  }

  private PlaybackDecision start() {
    return service
        .start(
            StartPlaybackSessionCommand.builder()
                .mediaFileId(UUID.randomUUID())
                .identity(profileScopedBuilder().build())
                .capabilityDeclaration(defaultDeclarationBuilder().build())
                .options(StreamingOptions.builder().build())
                .networkRoute(NetworkRoute.INTERNET)
                .build())
        .fold(
            snapshot -> snapshot.decisions().getFirst(),
            rejections -> {
              throw new AssertionError(rejections);
            });
  }

  @Test
  @DisplayName("Should preserve all source details when the file plays directly")
  void shouldPreserveAllSourceDetailsWhenTheFilePlaysDirectly() {
    var facts = profileSevenFacts();
    streams.shapeSessions(
        session ->
            session
                .delivery(StreamDelivery.DIRECT_PLAY)
                .mediaProbe(defaultProbeBuilder().videoPlaybackFacts(Optional.of(facts)).build()));

    var video = start().video();

    assertThat(video.source().details())
        .hasValueSatisfying(
            details -> {
              assertThat(details.contentLightLevel()).isEqualTo(facts.contentLightLevel());
              assertThat(details.dynamicRange()).isEqualTo(facts.dynamicRange());
            });
    assertThat(video.output()).isEqualTo(video.source());
  }

  @Test
  @DisplayName("Should leave source details unavailable when no probe facts were stored")
  void shouldLeaveSourceDetailsUnavailableWhenNoProbeFactsWereStored() {
    streams.shapeSessions(
        session ->
            session
                .delivery(StreamDelivery.DIRECT_PLAY)
                .mediaProbe(defaultProbeBuilder().videoPlaybackFacts(Optional.empty()).build()));

    assertThat(start().video().source().details()).isEmpty();
  }

  @Test
  @DisplayName("Should show stripped metadata only on the output when a copy removes Dolby Vision")
  void shouldShowStrippedMetadataOnlyOnTheOutputWhenACopyRemovesDolbyVision() {
    var facts = profileSevenFacts();
    streams.shapeSessions(
        session ->
            session
                .mediaProbe(defaultProbeBuilder().videoPlaybackFacts(Optional.of(facts)).build())
                .transcodeDecision(
                    remuxDecisionBuilder()
                        .videoSource(Optional.of(facts))
                        .videoBitstreamRewrites(List.of(BitstreamRewrite.STRIP_DOLBY_VISION))
                        .build()));

    var video = start().video();

    assertThat(video.source().dolbyVision()).isTrue();
    assertThat(video.output().dolbyVision()).isFalse();
    assertThat(video.output().details().orElseThrow().dynamicRange().dolbyVision())
        .isInstanceOf(DolbyVision.Absent.class);
    assertThat(video.output().details().orElseThrow().masteringDisplay())
        .isEqualTo(facts.masteringDisplay());
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoRange.class,
      names = {"PQ", "SDR", "HLG"})
  @DisplayName("Should describe the encoded format independently when the source has Dolby Vision")
  void shouldDescribeTheEncodedFormatIndependentlyWhenTheSourceHasDolbyVision(VideoRange range) {
    var facts = profileSevenFacts();
    streams.shapeSessions(
        session ->
            session
                .mediaProbe(defaultProbeBuilder().videoPlaybackFacts(Optional.of(facts)).build())
                .transcodeDecision(
                    fullTranscodeDecision("hevc").toBuilder()
                        .videoSource(Optional.of(facts))
                        .encodeCodecProfile(Optional.of(EncodeCodecProfile.HEVC_MAIN_10))
                        .videoEncodeFormat(new EncodeFormat(range, 10))
                        .build()));

    var video = start().video();
    var output = video.output().details().orElseThrow();

    assertThat(video.delivery()).isEqualTo(PlaybackDelivery.ENCODE);
    assertThat(video.output().videoRange()).isEqualTo(range);
    assertThat(output.bitDepth()).hasValue(10);
    assertThat(output.codecLevel()).isEmpty();
    assertThat(output.codecProfile()).hasValue(EncodeCodecProfile.HEVC_MAIN_10.codecProfile());
    assertThat(output.dynamicRange().dolbyVision()).isInstanceOf(DolbyVision.Absent.class);
    assertThat(output.bitstreamRewrites()).isEmpty();
    assertThat(output.masteringDisplay())
        .isEqualTo(range == VideoRange.PQ ? facts.masteringDisplay() : Optional.empty());
    assertThat(video.source().details().orElseThrow().dynamicRange())
        .isEqualTo(facts.dynamicRange());
  }

  @Test
  @DisplayName("Should report the planned record when the copy normalizes a Dolby Vision level")
  void shouldReportThePlannedRecordWhenTheCopyNormalizesADolbyVisionLevel() {
    var facts = profileSevenFacts();
    streams.shapeSessions(
        session ->
            session
                .mediaProbe(defaultProbeBuilder().videoPlaybackFacts(Optional.of(facts)).build())
                .transcodeDecision(
                    remuxDecisionBuilder()
                        .videoSource(Optional.of(facts))
                        .videoBitstreamRewrites(
                            List.of(
                                BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
                                BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
                        .videoDolbyVisionOutput(
                            Optional.of(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, 9)))
                        .build()));

    var video = start().video();

    assertThat(video.output().details().orElseThrow().dynamicRange().dolbyVision())
        .isInstanceOfSatisfying(
            DolbyVision.Present.class,
            dolby -> {
              assertThat(dolby.declared().orElseThrow().level()).hasValue(9);
              assertThat(dolby.declared().orElseThrow().profile()).hasValue(8);
              assertThat(dolby.declared().orElseThrow().baseLayerCompatibilityId()).hasValue(1);
            });
    assertThat(video.source().details().orElseThrow().dynamicRange())
        .isEqualTo(facts.dynamicRange());
  }

  private static VideoPlaybackFacts profileSevenFacts() {
    var dolbyVision =
        DolbyVision.Present.builder()
            .declared(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(7))
                        .level(OptionalInt.of(6))
                        .baseLayerCompatibilityId(OptionalInt.of(6))
                        .baseLayer(true)
                        .enhancementLayer(true)
                        .rpu(true)
                        .build()))
            .observedRpu(Presence.PRESENT)
            .enhancementLayer(DolbyVisionEnhancementLayer.FEL)
            .build();
    return VideoPlaybackFactsFixture.sdrH264FactsBuilder()
        .dynamicRange(new VideoDynamicRange(CompatibleBase.PQ, Presence.ABSENT, dolbyVision))
        .bitDepth(OptionalInt.of(10))
        .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_420))
        .masteringDisplay(Optional.of(VideoPlaybackFactsFixture.MASTERING_DISPLAY))
        .contentLightLevel(Optional.of(VideoPlaybackFactsFixture.CONTENT_LIGHT_LEVEL))
        .build();
  }
}
