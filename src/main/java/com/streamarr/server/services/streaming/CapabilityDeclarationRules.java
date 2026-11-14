package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioCapabilityEntry;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.AudioChannelsNotPositive;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.AudioDeliveryMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.AudioEntriesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecLevelNotPositive;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecProfileOutsideCodecFamily;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecProfilesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CompatibleBasesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.DirectPlaySampleEntriesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.DolbyVisionLevelNotPositive;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.DolbyVisionProfileOutsideCodecFamily;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.HlsAacEntryMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.MinBlockSizeNotApplicable;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.MinBlockSizeOutsideFlacBlockSizes;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyAudioDirectPlayContainers;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyAudioEntries;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyCodecProfiles;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyCompatibleBases;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyDirectPlaySampleEntries;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyDolbyVisionProfiles;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyVideoDirectPlayContainers;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyVideoEntries;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.VideoDeliveryMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.VideoEntriesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.VideoEntryMaximum;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.VideoMaximumInvalid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** The API boundary's checks of a client capability declaration (ADR 0049). */
final class CapabilityDeclarationRules {

  /** The longest list that a declaration may hold, generous for any measured player. */
  static final int MAXIMUM_LIST_SIZE = 32;

  // The block sizes that a FLAC stream's STREAMINFO block allows (RFC 9639, section 8.2).
  private static final int SMALLEST_FLAC_BLOCK_SIZE = 16;
  private static final int LARGEST_FLAC_BLOCK_SIZE = 65_535;

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
        .filter(maximum -> !isAcceptableMaximum(entry, maximum))
        .map(maximum -> new VideoMaximumInvalid(index, maximum))
        .forEach(rejections::add);
    rejections.addAll(codecProfileRejections(index, entry));
    compatibleBaseRejection(index, entry).ifPresent(rejections::add);
    rejections.addAll(dolbyVisionRejections(index, entry));
    videoDeliveryRejection(index, entry).ifPresent(rejections::add);
    sampleEntryRejection(index, entry).ifPresent(rejections::add);
    return rejections;
  }

  private static boolean isAcceptableMaximum(
      VideoCapabilityEntry entry, VideoEntryMaximum maximum) {
    return switch (maximum) {
      case BIT_DEPTH -> entry.maxBitDepth() > 0;
      case WIDTH -> entry.maxWidth() > 0;
      case HEIGHT -> entry.maxHeight() > 0;
      case FRAME_RATE -> Double.isFinite(entry.maxFrameRate()) && entry.maxFrameRate() > 0;
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
    }

    return rejections;
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

  // Profile 5 and 10.0 streams have no compatible base, so an entry that lists either needs none
  // (ADR 0049).
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
        .boxed()
        .flatMap(index -> audioEntryRejections(index, entries.get(index)).stream())
        .forEach(rejections::add);
    boolean declaresHlsAac =
        entries.stream().anyMatch(entry -> entry.codec() == AudioCodec.AAC && entry.hls());
    if (!declaresHlsAac) {
      rejections.add(new HlsAacEntryMissing());
    }

    return rejections;
  }

  private static List<CapabilityDeclarationRejection> audioEntryRejections(
      int index, AudioCapabilityEntry entry) {
    var rejections = new ArrayList<CapabilityDeclarationRejection>();
    if (entry.maxChannels() <= 0) {
      rejections.add(new AudioChannelsNotPositive(index));
    }

    audioDeliveryRejection(index, entry).ifPresent(rejections::add);
    minBlockSizeRejection(index, entry).ifPresent(rejections::add);
    return rejections;
  }

  private static Optional<CapabilityDeclarationRejection> minBlockSizeRejection(
      int index, AudioCapabilityEntry entry) {
    if (entry.minBlockSize().isEmpty()) {
      return Optional.empty();
    }

    if (entry.codec() != AudioCodec.FLAC) {
      return Optional.of(new MinBlockSizeNotApplicable(index));
    }

    int minBlockSize = entry.minBlockSize().getAsInt();
    if (minBlockSize < SMALLEST_FLAC_BLOCK_SIZE || minBlockSize > LARGEST_FLAC_BLOCK_SIZE) {
      return Optional.of(new MinBlockSizeOutsideFlacBlockSizes(index));
    }

    return Optional.empty();
  }

  // An entry applies to HLS, to direct play from the source containers that it lists, or to both
  // (ADR 0049), so an entry with neither applies to no delivery.
  private static Optional<CapabilityDeclarationRejection> videoDeliveryRejection(
      int index, VideoCapabilityEntry entry) {
    var containers = entry.directPlayContainers();
    if (containers.size() > MAXIMUM_LIST_SIZE) {
      return Optional.of(new TooManyVideoDirectPlayContainers(index, MAXIMUM_LIST_SIZE));
    }

    if (!entry.hls() && containers.isEmpty()) {
      return Optional.of(new VideoDeliveryMissing(index));
    }

    return Optional.empty();
  }

  // Only the player knows which sample entries it opens from MP4 or MOV, so an entry that lists
  // either names them rather than accepting every sample entry by leaving the list out (ADR 0045).
  // An overlong container list goes unchecked, so it never leads to this check.
  private static Optional<CapabilityDeclarationRejection> sampleEntryRejection(
      int index, VideoCapabilityEntry entry) {
    var sampleEntries = entry.directPlaySampleEntries();
    if (sampleEntries.size() > MAXIMUM_LIST_SIZE) {
      return Optional.of(new TooManyDirectPlaySampleEntries(index, MAXIMUM_LIST_SIZE));
    }

    var containers = entry.directPlayContainers();
    if (sampleEntries.isEmpty()
        && containers.size() <= MAXIMUM_LIST_SIZE
        && containers.stream().anyMatch(SourceContainer::storesSampleEntries)) {
      return Optional.of(new DirectPlaySampleEntriesMissing(index));
    }

    return Optional.empty();
  }

  private static Optional<CapabilityDeclarationRejection> audioDeliveryRejection(
      int index, AudioCapabilityEntry entry) {
    var containers = entry.directPlayContainers();
    if (containers.size() > MAXIMUM_LIST_SIZE) {
      return Optional.of(new TooManyAudioDirectPlayContainers(index, MAXIMUM_LIST_SIZE));
    }

    if (!entry.hls() && containers.isEmpty()) {
      return Optional.of(new AudioDeliveryMissing(index));
    }

    return Optional.empty();
  }
}
