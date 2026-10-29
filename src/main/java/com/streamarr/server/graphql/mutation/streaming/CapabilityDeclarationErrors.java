package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.graphql.mutation.streaming.CreateStreamSessionError.CapabilityListTooLongError;
import com.streamarr.server.graphql.mutation.streaming.CreateStreamSessionError.CapabilityMaximumInvalidError;
import com.streamarr.server.graphql.mutation.streaming.CreateStreamSessionError.CapabilityProfileMismatchError;
import com.streamarr.server.graphql.mutation.streaming.CreateStreamSessionError.CapabilityValuesRequiredError;
import com.streamarr.server.graphql.mutation.streaming.CreateStreamSessionError.HlsAacCapabilityRequiredError;
import com.streamarr.server.graphql.mutation.streaming.CreateStreamSessionError.VideoCodecTierInvalidError;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection;
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
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.VideoMaximumNotPositive;
import java.util.List;
import java.util.stream.Stream;

/** Maps each refused part of a capability declaration to its error and the input field it names. */
public final class CapabilityDeclarationErrors {

  private static final String CAPABILITIES = "capabilities";
  private static final String VIDEO = "video";
  private static final String AUDIO = "audio";
  private static final String CODEC_PROFILES = "codecProfiles";
  private static final String DOLBY_VISION_PROFILES = "dolbyVisionProfiles";
  private static final String MAX_LEVEL = "maxLevel";
  private static final String TOO_MANY = "List no more than %d values here.";
  private static final String NOT_POSITIVE = "Enter a maximum above zero.";

  private CapabilityDeclarationErrors() {}

  public static CreateStreamSessionError toError(CapabilityDeclarationRejection rejection) {
    return switch (rejection) {
      case VideoEntriesMissing() ->
          new CapabilityValuesRequiredError(
              "Declare at least one video capability entry.", path(VIDEO));
      case AudioEntriesMissing() ->
          new CapabilityValuesRequiredError(
              "Declare at least one audio capability entry.", path(AUDIO));
      case HlsAacEntryMissing() ->
          new HlsAacCapabilityRequiredError(
              "Declare an AAC audio capability entry that applies to HLS.", path(AUDIO));
      case TooManyVideoEntries(var maximum) -> tooMany(maximum, path(VIDEO));
      case TooManyAudioEntries(var maximum) -> tooMany(maximum, path(AUDIO));
      case CodecProfilesMissing(var entry) ->
          new CapabilityValuesRequiredError(
              "Declare at least one codec profile.", videoPath(entry, CODEC_PROFILES));
      case TooManyCodecProfiles(var entry, var maximum) ->
          tooMany(maximum, videoPath(entry, CODEC_PROFILES));
      case CompatibleBasesMissing(var entry) ->
          new CapabilityValuesRequiredError(
              "Declare at least one compatible base.", videoPath(entry, "compatibleBases"));
      case TooManyCompatibleBases(var entry, var maximum) ->
          tooMany(maximum, videoPath(entry, "compatibleBases"));
      case TooManyDolbyVisionProfiles(var entry, var maximum) ->
          tooMany(maximum, videoPath(entry, DOLBY_VISION_PROFILES));
      case VideoMaximumNotPositive(var entry, var maximum) ->
          new CapabilityMaximumInvalidError(NOT_POSITIVE, videoPath(entry, fieldOf(maximum)));
      case CodecProfileOutsideCodecFamily(var entry, var profile) ->
          new CapabilityProfileMismatchError(
              "Declare only codec profiles of the entry's codec family.",
              videoPath(entry, CODEC_PROFILES, Integer.toString(profile), "profile"));
      case CodecLevelNotPositive(var entry, var profile) ->
          new CapabilityMaximumInvalidError(
              NOT_POSITIVE, videoPath(entry, CODEC_PROFILES, Integer.toString(profile), MAX_LEVEL));
      case CodecTierMissing(var entry, var profile) ->
          new VideoCodecTierInvalidError(
              "Declare the maximum tier of an HEVC or AV1 codec profile.",
              videoPath(entry, CODEC_PROFILES, Integer.toString(profile), "maxTier"));
      case CodecTierNotApplicable(var entry, var profile) ->
          new VideoCodecTierInvalidError(
              "Leave out the maximum tier of an H.264 codec profile. H.264 has no tier.",
              videoPath(entry, CODEC_PROFILES, Integer.toString(profile), "maxTier"));
      case DolbyVisionProfileOutsideCodecFamily(var entry, var profile) ->
          new CapabilityProfileMismatchError(
              "Declare Dolby Vision profiles 5 and 8 in an HEVC entry, and 10 in an AV1 entry.",
              videoPath(entry, DOLBY_VISION_PROFILES, Integer.toString(profile), "profile"));
      case DolbyVisionLevelNotPositive(var entry, var profile) ->
          new CapabilityMaximumInvalidError(
              NOT_POSITIVE,
              videoPath(entry, DOLBY_VISION_PROFILES, Integer.toString(profile), MAX_LEVEL));
      case AudioChannelsNotPositive(var entry) ->
          new CapabilityMaximumInvalidError(NOT_POSITIVE, audioPath(entry, "maxChannels"));
    };
  }

  private static String fieldOf(VideoEntryMaximum maximum) {
    return switch (maximum) {
      case BIT_DEPTH -> "maxBitDepth";
      case WIDTH -> "maxWidth";
      case HEIGHT -> "maxHeight";
      case FRAME_RATE -> "maxFrameRate";
    };
  }

  private static CapabilityListTooLongError tooMany(int maximum, List<String> inputPath) {
    return new CapabilityListTooLongError(TOO_MANY.formatted(maximum), inputPath, maximum);
  }

  private static List<String> videoPath(int entry, String... rest) {
    return withinCapabilities(InputPath.element(VIDEO, entry, rest));
  }

  private static List<String> audioPath(int entry, String field) {
    return withinCapabilities(InputPath.element(AUDIO, entry, field));
  }

  private static List<String> path(String list) {
    return InputPath.of(CAPABILITIES, list);
  }

  private static List<String> withinCapabilities(List<String> path) {
    return Stream.concat(Stream.of(CAPABILITIES), path.stream()).toList();
  }
}
