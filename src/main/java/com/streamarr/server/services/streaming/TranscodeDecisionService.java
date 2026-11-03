package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.AudioSteps;
import com.streamarr.server.domain.streaming.AudioTrackChannels;
import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncodedPictureSizes;
import com.streamarr.server.domain.streaming.EncodedVariantRequest;
import com.streamarr.server.domain.streaming.HlsAudioEncodes;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MediaFileProbePropertyIssue;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.StreamCopySegments;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.SubtitleMode;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoStreamCopy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TranscodeDecisionService {

  // An encode forces one codec profile for each codec family (ADR 0013). These codec families
  // compress better than H.264, best first (ADR 0009).
  private static final List<EncodeCodecProfile> COMPRESSING_BETTER_THAN_H264 =
      List.of(EncodeCodecProfile.AV1_MAIN);
  private static final List<BitstreamRewrite> STRIP_HDR10_PLUS =
      List.of(BitstreamRewrite.STRIP_HDR10_PLUS);
  private static final List<BitstreamRewrite> NORMALIZE_DOLBY_VISION_RECORD =
      List.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD);
  private static final List<BitstreamRewrite> STRIP_HDR10_PLUS_AND_NORMALIZE_DOLBY_VISION_RECORD =
      List.of(BitstreamRewrite.STRIP_HDR10_PLUS, BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD);
  private static final CopyPlan STRIPPING_DOLBY_VISION =
      new CopyPlan(List.of(BitstreamRewrite.STRIP_DOLBY_VISION), Optional.empty());
  private static final CopyPlan STRIPPING_DOLBY_VISION_AND_HDR10_PLUS =
      new CopyPlan(
          List.of(BitstreamRewrite.STRIP_DOLBY_VISION, BitstreamRewrite.STRIP_HDR10_PLUS),
          Optional.empty());
  private static final String STREAM_COPY_REFUSALS_METRIC =
      "streamarr.streaming.stream_copy_refusals";

  private final StreamingProperties properties;
  private final MeterRegistry meterRegistry;

  /**
   * The source container from which the stream session direct-plays the source file (ADR 0045), or
   * empty when it plays over HLS. Only a format attempt that may direct-play does, and only in
   * Original. The declaration must cover the video and every audio track from the file's source
   * container (ADR 0049). An interlaced source, a property issue that rules out a copy (ADR 0049)
   * and a forced image subtitle in the language of any audio track (#506) keep the file on HLS. The
   * keyframe grid, the interim HDR copy rule and the codec string bind only HLS copies. A copy in
   * Original ignores the viewer's limits (ADR 0050), and so does direct play.
   */
  public Optional<SourceContainer> decideDirectPlay(@NonNull DirectPlayRequest request) {
    if (request.videoFormatConstraint() != VideoFormatConstraint.DIRECT_PLAY_OR_COPY_OR_ENCODE
        || request.viewerLimits().quality() != VideoQuality.ORIGINAL) {
      return Optional.empty();
    }

    var source = request.source();
    if (!permitsDirectPlay(source)) {
      return Optional.empty();
    }

    return source
        .sourceContainer()
        .filter(container -> request.capabilityDeclaration().acceptsDirectPlay(source, container));
  }

  // The worker deinterlaces an interlaced source for HLS, and no player's handling of an
  // interlaced file was measured.
  private static boolean permitsDirectPlay(MediaProbe source) {
    return !source.isInterlaced()
        && !hasPropertyIssueThatRulesOutACopy(source)
        && !source.hasForcedImageSubtitleInAnAudioLanguage();
  }

  private static boolean hasPropertyIssueThatRulesOutACopy(MediaProbe source) {
    return source.videoPlaybackFacts().stream()
        .flatMap(facts -> MediaFileProbePropertyIssue.foundIn(facts.dynamicRange()).stream())
        .anyMatch(MediaFileProbePropertyIssue::rulesOutCopy);
  }

  /**
   * A copy needs a video capability entry for HLS that accepts the copy, and a source that fits the
   * viewer's limits or that no encode can play: the source has no encoded variant, or no video
   * capability entry for HLS accepts one in a codec that a connected worker's verified encoder
   * encodes, busy or not (ADR 0050). A copy whose planned output needs more than passing the source
   * through also needs a connected worker, busy or not, that delivers it (ADR 0036). An encode
   * uses, among the codecs that a worker-verified encoder encodes (ADR 0009), the one whose video
   * capability entry for HLS fits an encoded variant of the source at the highest frame rate (ADR
   * 0049), and the first in the codec preference on a tie (ADR 0009). The audio follows the audio
   * capability entries for HLS. Returns empty when neither a copy nor a supported encode satisfies
   * the request's constraints.
   */
  public Optional<TranscodeDecision> decide(@NonNull TranscodeDecisionRequest request) {
    var source = request.source();
    var declaration = request.capabilityDeclaration();
    var sessionAudio = decideAudio(source, declaration);
    var videoCopy = videoCopy(request, sessionAudio);
    boolean videoCopyable = videoCopy.isPresent();
    var encodeCodecProfile =
        videoCopyable ? Optional.<EncodeCodecProfile>empty() : selectEncodeCodecProfile(request);
    if (!videoCopyable && encodeCodecProfile.isEmpty()) {
      return Optional.empty();
    }

    var audioDecision = videoCopy.map(VideoCopy::audio).orElse(sessionAudio);
    var subtitleDecision = SubtitleDecision.exclude();

    var mode = resolveTranscodeMode(videoCopyable, audioDecision, subtitleDecision);
    var copyPlan = videoCopy.filter(_ -> mode.copiesVideo()).map(VideoCopy::plan);

    return Optional.of(
        TranscodeDecision.builder()
            .transcodeMode(mode)
            .videoCodecFamily(
                encodeCodecProfile
                    .map(EncodeCodecProfile::codecFamilyName)
                    .orElse(source.videoCodec()))
            .encodeCodecProfile(encodeCodecProfile)
            .audioDecision(audioDecision)
            .subtitleDecision(subtitleDecision)
            .videoSource(source.videoPlaybackFacts())
            .videoBitstreamRewrites(copyPlan.map(CopyPlan::bitstreamRewrites).orElseGet(List::of))
            .videoDolbyVisionOutput(copyPlan.flatMap(CopyPlan::dolbyVisionOutput))
            .build());
  }

  // The audio beside a video copy, and the plan of the copy.
  private record VideoCopy(AudioDecision audio, CopyPlan plan) {}

  // The bitstream rewrites of a copy, and its Dolby Vision when the playlist describes the copy as
  // Dolby Vision.
  private record CopyPlan(
      List<BitstreamRewrite> bitstreamRewrites, Optional<DolbyVisionOutput> dolbyVisionOutput) {}

  // A copy needs a source within the viewer's limits beside the first audio step at which the
  // copy's cost fits the maximum bitrate, so the audio steps down before the video is encoded (ADR
  // 0050). A copy over the viewer's limits beats refusing playback when no encode can play it: the
  // source has no output frame rate (#446) or no picture size that the worker encodes, or no video
  // capability entry accepts its encoded variant in a codec that a verified encoder encodes. Such a
  // copy takes the first step that fits, or else keeps the session's audio, because a lower step
  // would not bring it within the maximum either.
  private Optional<VideoCopy> videoCopy(
      TranscodeDecisionRequest request, AudioDecision sessionAudio) {
    if (request.videoFormatConstraint() == VideoFormatConstraint.ENCODE_ONLY) {
      return Optional.empty();
    }

    var source = request.source();
    var declaration = request.capabilityDeclaration();
    var limits = request.viewerLimits();
    var steps = AudioSteps.below(sessionAudio, AudioTrackChannels.of(source), declaration);
    var stepWithinMaximum =
        limits.audioStepBesideCopyOf(source, steps, CopyMediaPlaylist.of(source, properties));
    var withinLimits = limits.allowsCopyPictureOf(source) && stepWithinMaximum.isPresent();
    if (!withinLimits && hasVerifiedEncodeThatTheClientPlays(request)) {
      return Optional.empty();
    }

    var plan = acceptedVideoCopyPlan(source, declaration);
    if (plan.isEmpty() || !hasWorkerThatDeliversTheCopy(request, plan.get().bitstreamRewrites())) {
      return Optional.empty();
    }

    return Optional.of(new VideoCopy(stepWithinMaximum.orElse(sessionAudio), plan.get()));
  }

  // A worker that does not deliver a copy's planned output would fail the copy, so the client gets
  // the encode instead (ADR 0036). Busy workers count, so a lack of free slots refuses the session
  // for capacity and never turns the copy into an encode.
  private static boolean hasWorkerThatDeliversTheCopy(
      TranscodeDecisionRequest request, List<BitstreamRewrite> bitstreamRewrites) {
    var copy =
        PlannedVideoOutput.StreamCopy.of(
            request.source().videoPlaybackFacts().orElseThrow(), bitstreamRewrites);
    return !copy.requiresPlannedOutputSupport()
        || request.videoCopySupport().stream()
            .anyMatch(worker -> worker.deliversPlannedOutputOf(copy));
  }

  // Busy workers' verified encoders count, so a lack of free slots refuses a session for capacity
  // and never turns it into a copy. Connected workers without a verified encoder for any codec
  // that the declaration accepts are faulty and leave nothing to encode to. Without a connected
  // worker no encoder is verified either, but the stream session is refused for capacity before
  // any copy starts.
  private static boolean hasVerifiedEncodeThatTheClientPlays(TranscodeDecisionRequest request) {
    var source = request.source();
    var declaration = request.capabilityDeclaration();
    return Arrays.stream(EncodeCodecProfile.values())
        .filter(profile -> request.verifiedVideoCodecFamilies().contains(profile.codecFamilyName()))
        .map(profile -> encodedVariantRequest(source, profile))
        .flatMap(Optional::stream)
        .anyMatch(variantRequest -> declaration.fitHlsEncode(variantRequest).isPresent());
  }

  private Optional<CopyPlan> acceptedVideoCopyPlan(
      MediaProbe source, ClientCapabilityDeclaration declaration) {
    var plan = copyPlanBeforeTheKeyframeCheck(source, declaration);
    if (plan.isEmpty()) {
      return Optional.empty();
    }

    var refusal = streamCopyRefusal(source);
    refusal.ifPresent(this::countStreamCopyRefusal);
    if (refusal.isPresent()) {
      return Optional.empty();
    }

    return plan;
  }

  /**
   * Whether a video capability entry for HLS accepts a copy of the source and the copy rules allow
   * it, before the keyframe check that refuses a copy whose keyframes leave an interval empty.
   */
  static boolean isCopyAcceptedBeforeTheKeyframeCheck(
      MediaProbe source, ClientCapabilityDeclaration declaration) {
    return copyPlanBeforeTheKeyframeCheck(source, declaration).isPresent();
  }

  // The first copy plan that a video capability entry for HLS accepts, or empty when the copy rules
  // or every entry refuse each plan. Apple's HLS Authoring Specification item 1.14 requires
  // deinterlaced video, so the worker deinterlaces an interlaced source while it encodes
  // (streamarr/streamarr-server#653).
  private static Optional<CopyPlan> copyPlanBeforeTheKeyframeCheck(
      MediaProbe source, ClientCapabilityDeclaration declaration) {
    var copy = VideoStreamCopy.of(source);
    if (copy.isEmpty() || source.isInterlaced() || !permitsCopyUntilHdrCopies(source)) {
      return Optional.empty();
    }

    return copyPlans(copy.get()).stream()
        .filter(
            plan -> declaration.acceptsHlsCopy(copy.get().afterRewrites(plan.bitstreamRewrites())))
        .findFirst();
  }

  // The copies that the server can plan, the best picture first: the server picks one output for
  // each client and format attempt (ADR 0013). A copy that keeps Dolby Vision on a PQ or HLG base
  // writes the record that the playlist describes, and strips HDR10+ by default (ADR 0036). A
  // client that declares the base range but not the Dolby Vision gets the copy with Dolby Vision
  // stripped, and one that declares the base range but not HDR10+ gets the copy with HDR10+
  // stripped.
  private static List<CopyPlan> copyPlans(VideoStreamCopy copy) {
    var hasHdr10Plus = copy.dynamicRange().hdr10Plus() == Presence.PRESENT;
    var plans = new ArrayList<>(keepingDolbyVisionPlans(copy, hasHdr10Plus));
    if (!copy.hasDolbyVisionStrippableToAnHdrBase()) {
      return plans;
    }

    plans.add(STRIPPING_DOLBY_VISION);
    if (hasHdr10Plus) {
      plans.add(STRIPPING_DOLBY_VISION_AND_HDR10_PLUS);
    }

    return plans;
  }

  // Dolby Vision that no capability entry can name stays in these plans, and every entry refuses
  // them.
  private static List<CopyPlan> keepingDolbyVisionPlans(
      VideoStreamCopy copy, boolean hasHdr10Plus) {
    var dolbyVisionOutput = copy.dolbyVisionOutput();
    if (dolbyVisionOutput.isPresent()) {
      return List.of(new CopyPlan(keepingDolbyVisionRewrites(hasHdr10Plus), dolbyVisionOutput));
    }

    var passThrough = new CopyPlan(List.of(), Optional.empty());
    if (!hasHdr10Plus) {
      return List.of(passThrough);
    }

    return List.of(passThrough, new CopyPlan(STRIP_HDR10_PLUS, Optional.empty()));
  }

  private static List<BitstreamRewrite> keepingDolbyVisionRewrites(boolean hasHdr10Plus) {
    if (hasHdr10Plus) {
      return STRIP_HDR10_PLUS_AND_NORMALIZE_DOLBY_VISION_RECORD;
    }

    return NORMALIZE_DOLBY_VISION_RECORD;
  }

  /**
   * The frame rate at which one entry encodes the source-sized variant in the codec profile, or
   * empty when no entry fits. An encode takes the codec family with the fastest such rate. A source
   * without an output frame rate or a picture that the worker encodes at any size has no encoded
   * variant, and the stream session refuses its encode for that reason, so only the codec profile
   * is checked, at a frame rate of 0 for every family.
   */
  static OptionalDouble encodedFrameRate(
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
  // source-sized variant fits every variant (ADR 0049). The variant runs at the rate the encode
  // runs at, which the deinterlacer doubles for an interlaced source (#653).
  private static Optional<EncodedVariantRequest> encodedVariantRequest(
      MediaProbe source, EncodeCodecProfile profile) {
    var picture = source.displayedSize();
    if (!EncodedPictureSizes.hasEncodablePicture(picture)) {
      return Optional.empty();
    }

    return source
        .videoPlaybackFacts()
        .flatMap(VideoPlaybackFacts::encodedFrameRate)
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

  private static TranscodeMode resolveTranscodeMode(
      boolean videoCopyable, AudioDecision audio, SubtitleDecision subtitle) {
    boolean subtitleForcesTranscode = subtitle.mode() == SubtitleMode.BURN_IN;
    return TranscodeMode.of(videoCopyable && !subtitleForcesTranscode, audio.mode());
  }

  private AudioDecision decideAudio(MediaProbe source, ClientCapabilityDeclaration declaration) {
    if (source.audioCodec() == null) {
      return AudioDecision.none();
    }

    // A copy carries every source channel, so the copy rule and the announced count use the
    // source's count. Only an encode lays the channels out in a normalized layout.
    var track = AudioTrackChannels.of(source);
    int sourceChannels = track.count();
    var sourceCodecString =
        source.audioStreams().stream()
            .findFirst()
            .flatMap(StreamInfo::codecString)
            .filter(codecString -> isCopyableAudioCodecString(source.audioCodec(), codecString));
    var copyChannelMaximum = HlsAudioEncodes.channelMaximum(declaration, source.audioCodec());
    if (copyChannelMaximum.isPresent()
        && sourceChannels <= copyChannelMaximum.getAsInt()
        && sourceCodecString.isPresent()) {
      return AudioDecision.builder()
          .mode(AudioMode.COPY)
          .codec(source.audioCodec())
          .channels(sourceChannels)
          .bitrate(
              source
                  .audioBitrate()
                  .orElse(
                      AudioDecision.bitrateFor(
                          source.audioCodec(), AudioDecision.normalizeChannels(sourceChannels))))
          .sourceCodecString(sourceCodecString)
          .build();
    }

    return HlsAudioEncodes.forTrack(declaration, track);
  }

  private static boolean isCopyableAudioCodecString(String codec, String codecString) {
    return AudioCodec.ofProbedName(codec)
        .map(declarable -> declarable.coversCodecString(codecString))
        .orElse(true);
  }

  private Optional<EncodeCodecProfile> selectEncodeCodecProfile(TranscodeDecisionRequest request) {
    var declaration = request.capabilityDeclaration();
    var source = request.source();
    return codecPreference(request).stream()
        .filter(
            profile -> request.encodableVideoCodecFamilies().contains(profile.codecFamilyName()))
        .filter(
            profile -> !request.excludedEncodeCodecFamilies().contains(profile.codecFamilyName()))
        .flatMap(
            profile ->
                encodedFrameRate(declaration, source, profile).stream()
                    .mapToObj(rate -> new EncodeCandidate(profile, rate)))
        .reduce((preferred, next) -> next.frameRate() > preferred.frameRate() ? next : preferred)
        .map(EncodeCandidate::codecProfile);
  }

  // The codec families that a worker encodes in hardware, then H.264, then the rest (ADR 0009).
  private static List<EncodeCodecProfile> codecPreference(TranscodeDecisionRequest request) {
    var hardwareEncodable = request.hardwareEncodableVideoCodecFamilies();
    var encodedInHardware =
        COMPRESSING_BETTER_THAN_H264.stream()
            .collect(
                Collectors.partitioningBy(
                    profile -> hardwareEncodable.contains(profile.codecFamilyName())));
    return Stream.of(
            encodedInHardware.get(true),
            List.of(EncodeCodecProfile.H264_HIGH),
            encodedInHardware.get(false))
        .flatMap(List::stream)
        .toList();
  }

  private record EncodeCandidate(EncodeCodecProfile codecProfile, double frameRate) {}
}
