package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.AudioCapabilityEntry;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/** Client capability declarations of an SDR player that plays its entries over HLS. */
@UtilityClass
public class ClientCapabilityDeclarationFixture {

  /** One H.264 video capability entry and one stereo AAC audio capability entry. */
  public static ClientCapabilityDeclaration.ClientCapabilityDeclarationBuilder
      defaultDeclarationBuilder() {
    return ClientCapabilityDeclaration.builder()
        .video(List.of(h264EntryBuilder().build()))
        .audio(List.of(aacEntryBuilder().build()));
  }

  /** H.264 Constrained Baseline, Main and High up to level 5.2, 8-bit SDR, up to 4K at 60 fps. */
  public static VideoCapabilityEntry.VideoCapabilityEntryBuilder h264EntryBuilder() {
    return sdrEntryBuilder(ProbedCodecFamily.H264)
        .codecProfiles(
            List.of(
                h264Profile(VideoCodecProfile.H264_CONSTRAINED_BASELINE),
                h264Profile(VideoCodecProfile.H264_MAIN),
                h264Profile(VideoCodecProfile.H264_HIGH)));
  }

  /** HEVC Main and Main 10 up to Main tier level 5.1, SDR, up to 4K at 60 fps. */
  public static VideoCapabilityEntry.VideoCapabilityEntryBuilder hevcEntryBuilder() {
    return sdrEntryBuilder(ProbedCodecFamily.HEVC)
        .maxBitDepth(10)
        .codecProfiles(
            List.of(
                mainTierProfile(VideoCodecProfile.HEVC_MAIN),
                mainTierProfile(VideoCodecProfile.HEVC_MAIN_10)));
  }

  /** AV1 Main up to Main tier level 5.1, SDR, up to 4K at 60 fps. */
  public static VideoCapabilityEntry.VideoCapabilityEntryBuilder av1EntryBuilder() {
    return sdrEntryBuilder(ProbedCodecFamily.AV1)
        .maxBitDepth(10)
        .codecProfiles(List.of(mainTierProfile(VideoCodecProfile.AV1_MAIN)));
  }

  /** A stereo AAC audio capability entry that applies to HLS. */
  public static AudioCapabilityEntry.AudioCapabilityEntryBuilder aacEntryBuilder() {
    return audioEntryBuilder(AudioCodec.AAC);
  }

  /** A stereo audio capability entry for the codec that applies to HLS. */
  public static AudioCapabilityEntry.AudioCapabilityEntryBuilder audioEntryBuilder(
      AudioCodec codec) {
    return AudioCapabilityEntry.builder().codec(codec).maxChannels(2).hls(true);
  }

  private static VideoCapabilityEntry.VideoCapabilityEntryBuilder sdrEntryBuilder(
      ProbedCodecFamily codecFamily) {
    return VideoCapabilityEntry.builder()
        .codecFamily(codecFamily)
        .maxBitDepth(8)
        .maxWidth(3840)
        .maxHeight(2160)
        .maxFrameRate(60)
        .compatibleBases(List.of(CompatibleBase.SDR))
        .dolbyVisionProfiles(List.of())
        .hdr10Plus(false)
        .hls(true);
  }

  private static VideoCodecProfileCapability h264Profile(VideoCodecProfile profile) {
    return VideoCodecProfileCapability.builder().profile(profile).maxLevel(52).build();
  }

  private static VideoCodecProfileCapability mainTierProfile(VideoCodecProfile profile) {
    return VideoCodecProfileCapability.builder()
        .profile(profile)
        .maxLevel(51)
        .maxTier(Optional.of(VideoCodecTier.MAIN))
        .build();
  }
}
