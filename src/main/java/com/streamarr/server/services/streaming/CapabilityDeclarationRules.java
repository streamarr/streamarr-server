package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioCapabilityEntry;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * The API boundary's checks of a client capability declaration (ADR 0049). The server assumes no
 * capability for a client, so every maximum must be positive, and every entry must name values that
 * belong to its codec family. HEVC and AV1 codec profiles name a maximum tier, because a capability
 * entry gives every maximum; H.264 has no tier. An entry lists a compatible base unless it lists
 * Dolby Vision profile 5 or 10.0, whose streams have none. Each list holds at most {@link
 * #MAXIMUM_LIST_SIZE} values, and an overlong list is reported without checking its values.
 */
final class CapabilityDeclarationRules {

  /** The longest list that a declaration may hold, generous for any measured player. */
  static final int MAXIMUM_LIST_SIZE = 32;

  private CapabilityDeclarationRules() {}

  static List<CapabilityDeclarationRejection> rejectionsOf(
      ClientCapabilityDeclaration declaration) {
    var rejections = new ArrayList<CapabilityDeclarationRejection>();
    rejections.addAll(videoRejections(declaration.video()));
    rejections.addAll(audioRejections(declaration.audio()));
    return List.copyOf(rejections);
  }

  private static List<CapabilityDeclarationRejection> videoRejections(
      List<VideoCapabilityEntry> entries) {
    if (entries.isEmpty()) {
      return List.of(new VideoEntriesMissing());
    }

    if (entries.size() > MAXIMUM_LIST_SIZE) {
      return List.of(new TooManyVideoEntries(MAXIMUM_LIST_SIZE));
    }

    return IntStream.range(0, entries.size())
        .boxed()
        .flatMap(index -> videoEntryRejections(index, entries.get(index)).stream())
        .toList();
  }

  private static List<CapabilityDeclarationRejection> videoEntryRejections(
      int index, VideoCapabilityEntry entry) {
    var rejections = new ArrayList<CapabilityDeclarationRejection>();
    Arrays.stream(VideoEntryMaximum.values())
        .filter(maximum -> !isValid(entry, maximum))
        .map(maximum -> new VideoMaximumInvalid(index, maximum))
        .forEach(rejections::add);
    rejections.addAll(codecProfileRejections(index, entry));
    compatibleBaseRejection(index, entry).ifPresent(rejections::add);
    rejections.addAll(dolbyVisionRejections(index, entry));
    return rejections;
  }

  private static boolean isValid(VideoCapabilityEntry entry, VideoEntryMaximum maximum) {
    return switch (maximum) {
      case BIT_DEPTH -> entry.maxBitDepth() > 0;
      case WIDTH -> entry.maxWidth() > 0;
      case HEIGHT -> entry.maxHeight() > 0;
      case FRAME_RATE -> entry.maxFrameRate() > 0;
    };
  }

  private static List<CapabilityDeclarationRejection> codecProfileRejections(
      int entryIndex, VideoCapabilityEntry entry) {
    var profiles = entry.codecProfiles();
    if (profiles.isEmpty()) {
      return List.of(new CodecProfilesMissing(entryIndex));
    }

    if (profiles.size() > MAXIMUM_LIST_SIZE) {
      return List.of(new TooManyCodecProfiles(entryIndex, MAXIMUM_LIST_SIZE));
    }

    var rejections = new ArrayList<CapabilityDeclarationRejection>();
    for (var index = 0; index < profiles.size(); index++) {
      var capability = profiles.get(index);
      if (capability.profile().codecFamily() != entry.codecFamily()) {
        rejections.add(new CodecProfileOutsideCodecFamily(entryIndex, index));
      }

      if (capability.maxLevel() <= 0) {
        rejections.add(new CodecLevelNotPositive(entryIndex, index));
      }

      tierRejection(entryIndex, index, capability).ifPresent(rejections::add);
    }

    return rejections;
  }

  private static Optional<CapabilityDeclarationRejection> tierRejection(
      int entryIndex, int profileIndex, VideoCodecProfileCapability capability) {
    boolean hasTiers = capability.profile().codecFamily() != ProbedCodecFamily.H264;
    if (hasTiers && capability.maxTier().isEmpty()) {
      return Optional.of(new CodecTierMissing(entryIndex, profileIndex));
    }

    if (!hasTiers && capability.maxTier().isPresent()) {
      return Optional.of(new CodecTierNotApplicable(entryIndex, profileIndex));
    }

    return Optional.empty();
  }

  private static Optional<CapabilityDeclarationRejection> compatibleBaseRejection(
      int entryIndex, VideoCapabilityEntry entry) {
    if (entry.compatibleBases().isEmpty() && !listsDolbyVisionWithoutACompatibleBase(entry)) {
      return Optional.of(new CompatibleBasesMissing(entryIndex));
    }

    if (entry.compatibleBases().size() > MAXIMUM_LIST_SIZE) {
      return Optional.of(new TooManyCompatibleBases(entryIndex, MAXIMUM_LIST_SIZE));
    }

    return Optional.empty();
  }

  // A profile 5 or 10.0 output fits through its Dolby Vision profile alone (ADR 0049).
  private static boolean listsDolbyVisionWithoutACompatibleBase(VideoCapabilityEntry entry) {
    return entry.dolbyVisionProfiles().stream()
        .anyMatch(capability -> capability.profile().compatibleBase() == CompatibleBase.NONE);
  }

  private static List<CapabilityDeclarationRejection> dolbyVisionRejections(
      int entryIndex, VideoCapabilityEntry entry) {
    var profiles = entry.dolbyVisionProfiles();
    if (profiles.size() > MAXIMUM_LIST_SIZE) {
      return List.of(new TooManyDolbyVisionProfiles(entryIndex, MAXIMUM_LIST_SIZE));
    }

    var rejections = new ArrayList<CapabilityDeclarationRejection>();
    for (var index = 0; index < profiles.size(); index++) {
      var capability = profiles.get(index);
      if (capability.profile().codecFamily() != entry.codecFamily()) {
        rejections.add(new DolbyVisionProfileOutsideCodecFamily(entryIndex, index));
      }

      if (capability.maxLevel() <= 0) {
        rejections.add(new DolbyVisionLevelNotPositive(entryIndex, index));
      }
    }

    return rejections;
  }

  private static List<CapabilityDeclarationRejection> audioRejections(
      List<AudioCapabilityEntry> entries) {
    if (entries.isEmpty()) {
      return List.of(new AudioEntriesMissing(), new HlsAacEntryMissing());
    }

    if (entries.size() > MAXIMUM_LIST_SIZE) {
      return List.of(new TooManyAudioEntries(MAXIMUM_LIST_SIZE));
    }

    var rejections = new ArrayList<CapabilityDeclarationRejection>();
    IntStream.range(0, entries.size())
        .filter(index -> entries.get(index).maxChannels() <= 0)
        .mapToObj(AudioChannelsNotPositive::new)
        .forEach(rejections::add);
    boolean declaresHlsAac =
        entries.stream().anyMatch(entry -> entry.codec() == AudioCodec.AAC && entry.hls());
    if (!declaresHlsAac) {
      rejections.add(new HlsAacEntryMissing());
    }

    return rejections;
  }
}
