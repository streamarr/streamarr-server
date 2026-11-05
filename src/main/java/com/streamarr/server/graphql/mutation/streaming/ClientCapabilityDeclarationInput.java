package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.domain.streaming.AudioCapabilityEntry;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoSampleEntry;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The client capability declaration as the schema spells it. The schema's enums name the domain
 * enums' values, and its codec families and compatible bases are the declarable subsets.
 */
public record ClientCapabilityDeclarationInput(
    List<VideoCapabilityEntryInput> video,
    List<AudioCapabilityEntryInput> audio,
    boolean selectsAudioTrackInDirectPlay) {

  public ClientCapabilityDeclaration toDeclaration() {
    return ClientCapabilityDeclaration.builder()
        .video(video.stream().map(VideoCapabilityEntryInput::toEntry).toList())
        .audio(audio.stream().map(AudioCapabilityEntryInput::toEntry).toList())
        .selectsAudioTrackInDirectPlay(selectsAudioTrackInDirectPlay)
        .build();
  }

  public record VideoCapabilityEntryInput(
      ProbedCodecFamily codecFamily,
      List<VideoCodecProfileCapabilityInput> codecProfiles,
      int maxBitDepth,
      int maxWidth,
      int maxHeight,
      double maxFrameRate,
      List<CompatibleBase> compatibleBases,
      List<DolbyVisionProfileCapabilityInput> dolbyVisionProfiles,
      boolean hdr10Plus,
      boolean hls,
      List<SourceContainer> directPlayContainers,
      List<VideoSampleEntry> directPlaySampleEntries) {

    VideoCapabilityEntry toEntry() {
      return VideoCapabilityEntry.builder()
          .codecFamily(codecFamily)
          .codecProfiles(
              codecProfiles.stream().map(VideoCodecProfileCapabilityInput::toCapability).toList())
          .maxBitDepth(maxBitDepth)
          .maxWidth(maxWidth)
          .maxHeight(maxHeight)
          .maxFrameRate(maxFrameRate)
          .compatibleBases(compatibleBases)
          .dolbyVisionProfiles(
              dolbyVisionProfiles.stream()
                  .map(DolbyVisionProfileCapabilityInput::toCapability)
                  .toList())
          .hdr10Plus(hdr10Plus)
          .hls(hls)
          .directPlayContainers(directPlayContainers)
          .directPlaySampleEntries(directPlaySampleEntries)
          .build();
    }
  }

  public record VideoCodecProfileCapabilityInput(
      VideoCodecProfile profile, int maxLevel, VideoCodecTier maxTier) {

    VideoCodecProfileCapability toCapability() {
      return VideoCodecProfileCapability.builder()
          .profile(profile)
          .maxLevel(maxLevel)
          .maxTier(Optional.ofNullable(maxTier))
          .build();
    }
  }

  public record DolbyVisionProfileCapabilityInput(DolbyVisionProfile profile, int maxLevel) {

    DolbyVisionProfileCapability toCapability() {
      return DolbyVisionProfileCapability.builder().profile(profile).maxLevel(maxLevel).build();
    }
  }

  public record AudioCapabilityEntryInput(
      AudioCodec codec,
      int maxChannels,
      boolean hls,
      List<SourceContainer> directPlayContainers,
      Integer minBlockSize) {

    AudioCapabilityEntry toEntry() {
      return AudioCapabilityEntry.builder()
          .codec(codec)
          .maxChannels(maxChannels)
          .hls(hls)
          .directPlayContainers(directPlayContainers)
          .minBlockSize(minBlockSize == null ? OptionalInt.empty() : OptionalInt.of(minBlockSize))
          .build();
    }
  }
}
