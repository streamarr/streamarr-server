package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioCapabilityEntry;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncodedPictureSizes;
import com.streamarr.server.domain.streaming.EncodedVariantRequest;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.StreamCopySegments;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.SubtitleMode;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoStreamCopy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TranscodeDecisionService {

  // An encode forces one codec profile for each codec family (ADR 0013).
  private static final List<EncodeCodecProfile> CODEC_PREFERENCE =
      List.of(EncodeCodecProfile.AV1_MAIN, EncodeCodecProfile.H264_HIGH);
  // A multichannel encode prefers the first of these codecs that keeps more than two channels.
  private static final List<String> MULTICHANNEL_ENCODE_PREFERENCE = List.of("eac3", "ac3", "aac");
  // FFmpeg's AC-3 and E-AC-3 encoders stop at 5.1.
  private static final int AC3_AND_EAC3_ENCODER_MAX_CHANNELS = 6;
  private static final int STEREO = 2;
  // The plain aac declaration covers LC, HE-AAC and HE-AAC v2.
  private static final Set<String> COPYABLE_AAC_CODEC_STRINGS =
      Set.of("mp4a.40.2", "mp4a.40.5", "mp4a.40.29");
  private static final String STREAM_COPY_REFUSALS_METRIC =
      "streamarr.streaming.stream_copy_refusals";

  private final StreamingProperties properties;
  private final MeterRegistry meterRegistry;

  /**
   * A copy needs a video capability entry for HLS that accepts the copy. An encode uses, among the
   * codecs that a worker-verified encoder encodes (ADR 0009), the one whose video capability entry
   * for HLS fits an encoded variant of the source at the highest frame rate, and the first in the
   * preference on a tie (ADR 0049). The audio follows the audio capability entries for HLS. Returns
   * empty when neither a copy nor a supported encode satisfies the request's constraints.
   */
  public Optional<TranscodeDecision> decide(@NonNull TranscodeDecisionRequest request) {
    var source = request.source();
    var declaration = request.capabilityDeclaration();
    boolean videoCopyable =
        request.videoFormatConstraint() != VideoFormatConstraint.ENCODE_ONLY
            && decideVideoCopy(source, declaration);
    if (!videoCopyable && request.videoFormatConstraint() == VideoFormatConstraint.COPY_ONLY) {
      return Optional.empty();
    }

    var videoCodecFamily =
        videoCopyable ? Optional.of(source.videoCodec()) : selectPreferredCodec(request);
    var audioDecision = decideAudio(source, declaration);
    var subtitleDecision = SubtitleDecision.exclude();

    var mode = resolveTranscodeMode(videoCopyable, audioDecision, subtitleDecision);

    return videoCodecFamily.map(
        codec ->
            TranscodeDecision.builder()
                .transcodeMode(mode)
                .videoCodecFamily(codec)
                .audioDecision(audioDecision)
                .subtitleDecision(subtitleDecision)
                .videoSource(source.videoPlaybackFacts())
                .build());
  }

  private boolean decideVideoCopy(MediaProbe source, ClientCapabilityDeclaration declaration) {
    var copyFits = VideoStreamCopy.of(source).filter(declaration::acceptsHlsCopy).isPresent();
    if (!copyFits || !permitsCopyUntilHdrCopies(source)) {
      return false;
    }

    var refusal = streamCopyRefusal(source);
    refusal.ifPresent(this::countStreamCopyRefusal);
    return refusal.isEmpty();
  }

  // The frame rate at which one entry encodes the source-sized variant, or empty when no entry
  // fits. A source without an output frame rate or a picture that the worker encodes at any size
  // has no encoded variant, and the stream session refuses its encode for that reason, so only the
  // codec profile is checked, at a frame rate of 0 for every family.
  private static OptionalDouble encodedFrameRate(
      ClientCapabilityDeclaration declaration, MediaProbe source, EncodeCodecProfile profile) {
    var variantRequest = encodedVariantRequest(source, profile);
    if (variantRequest.isPresent()) {
      return declaration
          .fitHlsEncode(variantRequest.get())
          .map(output -> OptionalDouble.of(output.frameRate().toDouble()))
          .orElseGet(OptionalDouble::empty);
    }

    if (declaresHlsCodecProfile(declaration, profile.codecProfile())) {
      return OptionalDouble.of(0);
    }

    return OptionalDouble.empty();
  }

  // Every variant of a stream session is at most the source's size, so an entry that fits the
  // source-sized variant fits every variant (ADR 0049).
  private static Optional<EncodedVariantRequest> encodedVariantRequest(
      MediaProbe source, EncodeCodecProfile profile) {
    var picture = source.displayedSize();
    if (!EncodedPictureSizes.hasEncodablePicture(picture)) {
      return Optional.empty();
    }

    return source
        .videoPlaybackFacts()
        .flatMap(VideoPlaybackFacts::outputFrameRate)
        .map(
            frameRate ->
                EncodedVariantRequest.builder()
                    .codecProfile(profile)
                    .source(picture)
                    .bounds(EncodedPictureSizes.NO_BOUNDS)
                    .frameRate(frameRate)
                    .bitrate(source.bitrate())
                    .build());
  }

  private static boolean declaresHlsCodecProfile(
      ClientCapabilityDeclaration declaration, VideoCodecProfile profile) {
    return hlsVideoEntries(declaration)
        .filter(entry -> entry.codecFamily() == profile.codecFamily())
        .flatMap(entry -> entry.codecProfiles().stream())
        .anyMatch(capability -> capability.profile() == profile);
  }

  private static Stream<VideoCapabilityEntry> hlsVideoEntries(
      ClientCapabilityDeclaration declaration) {
    return declaration.video().stream().filter(VideoCapabilityEntry::hls);
  }

  // A source without playback facts has no codec string for a copy job to carry.
  private static boolean permitsCopyUntilHdrCopies(MediaProbe source) {
    return source
        .videoPlaybackFacts()
        .map(facts -> InterimVideoCopyRule.permitsCopy(source.videoCodec(), facts))
        .orElse(false);
  }

  // A stream copy's segments can start only at source keyframes (ADR 0037), so the video is copied
  // only when the known keyframes open every interval of the grid except possibly the last. A last
  // interval without a keyframe joins the copy segment before it, which then runs to the media end.
  private Optional<StreamCopyRefusal> streamCopyRefusal(MediaProbe source) {
    return switch (source.keyframes()) {
      case KeyframeKnowledge.Known(var positions) ->
          streamCopyRefusal(positions, MediaSegmentTimelines.of(source, properties));
      case KeyframeKnowledge.PacketScanRequired _ ->
          Optional.of(StreamCopyRefusal.PACKET_SCAN_REQUIRED);
      case KeyframeKnowledge.Unavailable _ -> Optional.of(StreamCopyRefusal.KEYFRAMES_UNAVAILABLE);
      case KeyframeKnowledge.NotReported _ -> Optional.of(StreamCopyRefusal.KEYFRAMES_NOT_REPORTED);
    };
  }

  private static Optional<StreamCopyRefusal> streamCopyRefusal(
      KeyframePositions positions, MediaSegmentTimeline.PlainGrid grid) {
    return switch (KeyframeSegmentBoundaries.forStreamCopy(
        positions, grid.targetSegmentDurationSeconds())) {
      case StreamCopySegments.Starts starts
          when !opensEveryIntervalButTheLast(grid, positions, starts) ->
          Optional.of(StreamCopyRefusal.INTERVAL_WITHOUT_KEYFRAME);
      case StreamCopySegments.Starts _ -> Optional.empty();
      case StreamCopySegments.IntervalWithoutKeyframe _ ->
          Optional.of(StreamCopyRefusal.INTERVAL_WITHOUT_KEYFRAME);
      case StreamCopySegments.PresentationTimeRegressed _ ->
          Optional.of(StreamCopyRefusal.PRESENTATION_TIME_REGRESSED);
      case StreamCopySegments.PresentationTimeOutOfRange _ ->
          Optional.of(StreamCopyRefusal.PRESENTATION_TIME_OUT_OF_RANGE);
      case StreamCopySegments.DecodeTimeOutOfRange _ ->
          Optional.of(StreamCopyRefusal.DECODE_TIME_OUT_OF_RANGE);
    };
  }

  private static boolean opensEveryIntervalButTheLast(
      MediaSegmentTimeline.PlainGrid grid,
      KeyframePositions positions,
      StreamCopySegments.Starts starts) {
    var copySegmentCount = grid.openedAt(positions, starts).mediaSegmentCount();
    return copySegmentCount > 0 && copySegmentCount >= grid.mediaSegmentCount() - 1;
  }

  private void countStreamCopyRefusal(StreamCopyRefusal refusal) {
    Counter.builder(STREAM_COPY_REFUSALS_METRIC)
        .description(
            "Stream copies of a video codec the client plays, refused because the source's"
                + " keyframes are not known or leave a grid interval before the last one"
                + " without a keyframe")
        .tag("reason", refusal.name().toLowerCase(Locale.ROOT))
        .register(meterRegistry)
        .increment();
  }

  private enum StreamCopyRefusal {
    KEYFRAMES_NOT_REPORTED,
    PACKET_SCAN_REQUIRED,
    KEYFRAMES_UNAVAILABLE,
    INTERVAL_WITHOUT_KEYFRAME,
    PRESENTATION_TIME_REGRESSED,
    PRESENTATION_TIME_OUT_OF_RANGE,
    DECODE_TIME_OUT_OF_RANGE
  }

  private TranscodeMode resolveTranscodeMode(
      boolean videoCopyable, AudioDecision audio, SubtitleDecision subtitle) {
    boolean subtitleForcesTranscode = subtitle.mode() == SubtitleMode.BURN_IN;
    boolean effectiveVideoCopyable = videoCopyable && !subtitleForcesTranscode;
    boolean audioPassthrough = audio.mode() == AudioMode.COPY || audio.mode() == AudioMode.NONE;

    if (effectiveVideoCopyable && audioPassthrough) {
      return TranscodeMode.REMUX;
    }
    if (effectiveVideoCopyable) {
      return TranscodeMode.AUDIO_TRANSCODE;
    }
    if (audioPassthrough) {
      return TranscodeMode.VIDEO_TRANSCODE;
    }
    return TranscodeMode.FULL_TRANSCODE;
  }

  private AudioDecision decideAudio(MediaProbe source, ClientCapabilityDeclaration declaration) {
    if (source.audioCodec() == null) {
      return AudioDecision.none();
    }

    var channelMaximums = hlsAudioChannelMaximums(declaration);
    // A copy carries every source channel, so the copy rule and the announced count use the
    // source's count. Only an encode lays the channels out in a normalized layout.
    int sourceChannels = source.audioChannels().orElse(STEREO);
    var sourceCodecString =
        source.audioStreams().stream()
            .findFirst()
            .flatMap(StreamInfo::codecString)
            .filter(codecString -> isCopyableAudioCodecString(source.audioCodec(), codecString));
    var copyChannelMaximum = Optional.ofNullable(channelMaximums.get(source.audioCodec()));
    if (copyChannelMaximum.filter(maximum -> sourceChannels <= maximum).isPresent()
        && sourceCodecString.isPresent()) {
      return AudioDecision.builder()
          .mode(AudioMode.COPY)
          .codec(source.audioCodec())
          .channels(sourceChannels)
          .bitrate(
              source
                  .audioBitrate()
                  .orElse(
                      AudioDecision.bitrateForChannels(
                          AudioDecision.normalizeChannels(sourceChannels))))
          .sourceCodecString(sourceCodecString)
          .build();
    }

    return selectTranscodeAudio(channelMaximums, AudioDecision.normalizeChannels(sourceChannels));
  }

  // Several entries for one codec each accept up to their own maximum, so the codec accepts up to
  // the largest of them.
  private static Map<String, Integer> hlsAudioChannelMaximums(
      ClientCapabilityDeclaration declaration) {
    return declaration.audio().stream()
        .filter(AudioCapabilityEntry::hls)
        .filter(entry -> AudioDecision.deliverableCodecs().contains(entry.codec().probedName()))
        .collect(
            Collectors.toMap(
                entry -> entry.codec().probedName(), AudioCapabilityEntry::maxChannels, Math::max));
  }

  private static boolean isCopyableAudioCodecString(String codec, String codecString) {
    return !"aac".equals(codec) || COPYABLE_AAC_CODEC_STRINGS.contains(codecString);
  }

  // The API boundary requires an AAC entry for HLS, so ADR 0043's AAC encode always fits.
  private AudioDecision selectTranscodeAudio(
      Map<String, Integer> channelMaximums, int normalizedChannels) {
    for (var codec : MULTICHANNEL_ENCODE_PREFERENCE) {
      int channels =
          Math.min(
              normalizedChannels,
              Math.min(channelMaximums.getOrDefault(codec, 0), encoderChannelLimit(codec)));
      if (channels > STEREO) {
        return transcode(codec, channels);
      }
    }

    int aacMaximum = channelMaximums.getOrDefault("aac", STEREO);
    return transcode("aac", Math.min(Math.min(normalizedChannels, STEREO), aacMaximum));
  }

  private static int encoderChannelLimit(String codec) {
    return switch (codec) {
      case "ac3", "eac3" -> AC3_AND_EAC3_ENCODER_MAX_CHANNELS;
      default -> Integer.MAX_VALUE;
    };
  }

  private AudioDecision transcode(String codec, int channels) {
    return AudioDecision.builder()
        .mode(AudioMode.TRANSCODE)
        .codec(codec)
        .channels(channels)
        .bitrate(AudioDecision.bitrateForChannels(channels))
        .build();
  }

  private Optional<String> selectPreferredCodec(TranscodeDecisionRequest request) {
    var declaration = request.capabilityDeclaration();
    var source = request.source();
    return CODEC_PREFERENCE.stream()
        .filter(
            profile -> request.encodableVideoCodecFamilies().contains(profile.codecFamilyName()))
        .filter(
            profile -> !request.excludedEncodeCodecFamilies().contains(profile.codecFamilyName()))
        .flatMap(
            profile ->
                encodedFrameRate(declaration, source, profile).stream()
                    .mapToObj(rate -> new EncodeCandidate(profile.codecFamilyName(), rate)))
        .reduce((preferred, next) -> next.frameRate() > preferred.frameRate() ? next : preferred)
        .map(EncodeCandidate::codecFamily);
  }

  private record EncodeCandidate(String codecFamily, double frameRate) {}
}
