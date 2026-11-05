package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.AudioSteps;
import com.streamarr.server.domain.streaming.AudioStreamProperties;
import com.streamarr.server.domain.streaming.AudioTrackChannels;
import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.DecisionReason;
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
import java.util.OptionalInt;
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
   * Whether the stream session direct-plays the source file from its source container (ADR 0045),
   * or plays over HLS and why. Only a format attempt that may direct-play does, and only in
   * Original; any other format attempt names no refusal. The declaration must cover the video and
   * every audio track from the file's source container (ADR 0049). The decision names the stream
   * session's audio track, which a player that selects audio tracks selects. A player that cannot
   * select one starts on the file's first audio track, unless an MP4 or MOV file's enabled flags
   * start Chromium on another, so it direct-plays only when the session's track is the first and
   * every such player starts on it (ADR 0045). An interlaced source, a property issue that rules
   * out a copy (ADR 0049) and a forced image subtitle in the language of any audio track (#506)
   * keep the file on HLS. The refusals name every check that the file fails. The keyframe grid, the
   * interim HDR copy rule and the codec string bind only HLS copies. A copy in Original ignores the
   * viewer's limits (ADR 0050), and so does direct play.
   */
  public DirectPlayChoice decideDirectPlay(@NonNull DirectPlayRequest request) {
    if (request.videoFormatConstraint() != VideoFormatConstraint.DIRECT_PLAY_OR_COPY_OR_ENCODE
        || request.viewerLimits().quality() != VideoQuality.ORIGINAL) {
      return new DirectPlayChoice.Refused(List.of());
    }

    var source = request.source();
    var refusals = new ArrayList<>(sourceDirectPlayRefusals(source));
    var container = source.sourceContainer();
    if (container.isEmpty()) {
      refusals.add(DecisionReason.SOURCE_CONTAINER_UNKNOWN);
      return new DirectPlayChoice.Refused(refusals);
    }

    var declaration = request.capabilityDeclaration();
    refusals.addAll(declaration.directPlayRefusals(source, container.get()));
    if (!canPlayTheSessionsAudioTrack(declaration, source)) {
      refusals.add(DecisionReason.DIRECT_PLAY_AUDIO_TRACK_NOT_SELECTABLE);
    }

    if (!refusals.isEmpty()) {
      return new DirectPlayChoice.Refused(refusals);
    }

    return new DirectPlayChoice.Accepted(container.get(), source.sessionAudioTrackPosition());
  }

  private static boolean canPlayTheSessionsAudioTrack(
      ClientCapabilityDeclaration declaration, MediaProbe source) {
    return declaration.selectsAudioTrackInDirectPlay()
        || (source.sessionAudioTrackPosition().orElse(0) == 0
            && source.startsOnTheFirstAudioTrack());
  }

  // No player's direct play of an interlaced file was measured.
  private static List<DecisionReason> sourceDirectPlayRefusals(MediaProbe source) {
    var refusals = new ArrayList<DecisionReason>();
    if (source.isInterlaced()) {
      refusals.add(DecisionReason.SOURCE_INTERLACED);
    }

    source.videoPlaybackFacts().stream()
        .flatMap(facts -> MediaFileProbePropertyIssue.foundIn(facts.dynamicRange()).stream())
        .filter(MediaFileProbePropertyIssue::rulesOutCopy)
        .map(InterimVideoCopyRule::reasonFor)
        .forEach(refusals::add);
    if (source.hasForcedImageSubtitleInAnAudioLanguage()) {
      refusals.add(DecisionReason.FORCED_IMAGE_SUBTITLE_IN_AUDIO_LANGUAGE);
    }

    return refusals;
  }

  /**
   * A copy needs a video capability entry for HLS that accepts the copy, and a source that fits the
   * viewer's limits or that no encode can play: the source has no encoded variant, or no video
   * capability entry for HLS accepts one in a codec that a connected worker's verified encoder
   * encodes, busy or not (ADR 0050). A copy whose planned output needs more than passing the source
   * through also needs a connected worker, busy or not, that delivers it (ADR 0036), including the
   * Dolby Vision sample entry of a profile 5 or 10.0 copy (ADR 0013). An encode uses, among the
   * codecs that a worker-verified encoder encodes (ADR 0009), the one whose video capability entry
   * for HLS fits an encoded variant of the source at the highest frame rate (ADR 0049), and the
   * first in the codec preference on a tie (ADR 0009). The audio follows the audio capability
   * entries for HLS. Returns empty when neither a copy nor a supported encode satisfies the
   * request's constraints.
   */
  public Optional<TranscodeDecision> decide(@NonNull TranscodeDecisionRequest request) {
    var source = request.source();
    var declaration = request.capabilityDeclaration();
    var sessionAudio = decideAudio(source, declaration);
    var videoCopy = videoCopy(request, sessionAudio.decision());
    var copy =
        switch (videoCopy) {
          case VideoCopyChoice.Copied copied -> Optional.of(copied);
          case VideoCopyChoice.Refused _ -> Optional.<VideoCopyChoice.Copied>empty();
        };
    boolean videoCopyable = copy.isPresent();
    var encodeCodecProfile =
        videoCopyable ? Optional.<EncodeCodecProfile>empty() : selectEncodeCodecProfile(request);
    if (!videoCopyable && encodeCodecProfile.isEmpty()) {
      return Optional.empty();
    }

    var audioDecision = copy.map(VideoCopyChoice.Copied::audio).orElse(sessionAudio.decision());
    var subtitleDecision = SubtitleDecision.exclude();

    var mode = resolveTranscodeMode(videoCopyable, audioDecision, subtitleDecision);
    var copyPlan = copy.filter(_ -> mode.copiesVideo()).map(VideoCopyChoice.Copied::plan);

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
            .reasons(
                afterDirectPlayRefusals(request, reasons(videoCopy, sessionAudio, audioDecision)))
            .audioStreamIndex(audioStreamIndex(source, audioDecision))
            .build());
  }

  // Every decision about the audio reads the source's first audio stream, the session's.
  private static OptionalInt audioStreamIndex(MediaProbe source, AudioDecision audio) {
    if (audio.mode() == AudioMode.NONE) {
      return OptionalInt.empty();
    }

    return source.audioStreams().stream().mapToInt(StreamInfo::index).findFirst();
  }

  // Video reasons come first. Audio that a copy lowers below the session's audio for the viewer's
  // bitrate limit names that limit beside any reason that the session's audio is encoded.
  private static List<DecisionReason> reasons(
      VideoCopyChoice videoCopy, SessionAudio sessionAudio, AudioDecision audio) {
    var reasons = new ArrayList<>(videoCopy.reasons());
    sessionAudio.encodeReason().ifPresent(reasons::add);
    if (!audio.equals(sessionAudio.decision())) {
      reasons.add(DecisionReason.AUDIO_LOWERED_FOR_BITRATE_LIMIT);
    }

    return reasons;
  }

  // Direct play and a copy can refuse for the same fact, such as an interlaced source, which the
  // decision names once.
  private static List<DecisionReason> afterDirectPlayRefusals(
      TranscodeDecisionRequest request, List<DecisionReason> decisionReasons) {
    return Stream.concat(request.directPlayRefusals().stream(), decisionReasons.stream())
        .distinct()
        .toList();
  }

  // Whether the decision copies the video, with the audio beside the copy and its plan, and why the
  // copy leaves part of the source out or why the video is not copied.
  private sealed interface VideoCopyChoice {
    List<DecisionReason> reasons();

    record Copied(AudioDecision audio, CopyPlan plan, List<DecisionReason> reasons)
        implements VideoCopyChoice {}

    record Refused(List<DecisionReason> reasons) implements VideoCopyChoice {}
  }

  // The bitstream rewrites of a copy, and its Dolby Vision when the playlist describes the copy as
  // Dolby Vision.
  private record CopyPlan(
      List<BitstreamRewrite> bitstreamRewrites, Optional<DolbyVisionOutput> dolbyVisionOutput) {}

  // The session's audio, and why it is encoded when it is.
  private record SessionAudio(AudioDecision decision, Optional<DecisionReason> encodeReason) {}

  // A copy needs a source within the viewer's limits beside the first audio step at which the
  // copy's cost fits the maximum bitrate, so the audio steps down before the video is encoded (ADR
  // 0050). A copy over the viewer's limits beats refusing playback when no encode can play it: the
  // source has no output frame rate (#446) or no picture size that the worker encodes, or no video
  // capability entry accepts its encoded variant in a codec that a verified encoder encodes. Such a
  // copy takes the first step that fits, or else keeps the session's audio, because a lower step
  // would not bring it within the maximum either. A format attempt that must encode names no
  // reason here: the playback session names the failure that led to it.
  private VideoCopyChoice videoCopy(TranscodeDecisionRequest request, AudioDecision sessionAudio) {
    var encodeOnly = request.videoFormatConstraint() == VideoFormatConstraint.ENCODE_ONLY;
    var choice = copyChoice(request, sessionAudio, !encodeOnly);
    if (!encodeOnly) {
      return choice;
    }

    return switch (choice) {
      case VideoCopyChoice.Refused refused -> refused;
      case VideoCopyChoice.Copied _ -> new VideoCopyChoice.Refused(List.of());
    };
  }

  // Only a decision that may copy counts its keyframe refusals: an encode-only decision checks
  // the copy again only to name why it was refused.
  private VideoCopyChoice copyChoice(
      TranscodeDecisionRequest request, AudioDecision sessionAudio, boolean countsRefusals) {
    var source = request.source();
    var declaration = request.capabilityDeclaration();
    var limits = request.viewerLimits();
    var steps = AudioSteps.below(sessionAudio, AudioTrackChannels.of(source), declaration);
    var playlist = CopyMediaPlaylist.of(source, properties);
    var stepWithinMaximum = limits.audioStepBesideCopyOf(source, steps, playlist);
    var limitRefusals = viewerLimitRefusals(limits.allowsCopyPictureOf(source), stepWithinMaximum);
    if (!limitRefusals.isEmpty() && hasVerifiedEncodeThatTheClientPlays(request)) {
      return new VideoCopyChoice.Refused(beforeViewerLimits(request, limitRefusals));
    }

    var audio = stepWithinMaximum.orElse(sessionAudio);
    return switch (acceptedVideoCopyPlan(request, countsRefusals)) {
      case PlanChoice.Refused(var reasons) -> new VideoCopyChoice.Refused(reasons);
      case PlanChoice.Accepted(var plan, _) when !hasWorkerThatDeliversTheCopy(request, plan) ->
          new VideoCopyChoice.Refused(List.of(DecisionReason.NO_WORKER_DELIVERS_COPY));
      case PlanChoice.Accepted _
          when VideoCopyBandwidth.exceedsSegmentCap(source, audio, playlist) ->
          segmentCapRefusal(countsRefusals);
      case PlanChoice.Accepted(var plan, var reasons) ->
          new VideoCopyChoice.Copied(audio, plan, reasons);
    };
  }

  private VideoCopyChoice segmentCapRefusal(boolean countsRefusals) {
    if (countsRefusals) {
      countStreamCopyRefusal(StreamCopyRefusal.EXCEEDS_SEGMENT_CAP);
    }

    return new VideoCopyChoice.Refused(List.of(StreamCopyRefusal.EXCEEDS_SEGMENT_CAP.reason()));
  }

  // A copy that the viewer's limits refuse may also be one that the source or the declaration
  // refuses, which a higher limit would not change, so those reasons come first. The stream-copy
  // refusal metric counts only the copies that the limits let through.
  private List<DecisionReason> beforeViewerLimits(
      TranscodeDecisionRequest request, List<DecisionReason> limitRefusals) {
    var reasons = new ArrayList<DecisionReason>();
    var plan = acceptedVideoCopyPlan(request, false);
    if (plan instanceof PlanChoice.Refused(var refused)) {
      reasons.addAll(refused);
    }

    reasons.addAll(limitRefusals);
    return reasons;
  }

  private static List<DecisionReason> viewerLimitRefusals(
      boolean pictureWithinLimits, Optional<AudioDecision> stepWithinMaximum) {
    var refusals = new ArrayList<DecisionReason>();
    if (!pictureWithinLimits) {
      refusals.add(DecisionReason.PICTURE_ABOVE_VIEWER_LIMIT);
    }

    if (stepWithinMaximum.isEmpty()) {
      refusals.add(DecisionReason.BITRATE_ABOVE_VIEWER_LIMIT);
    }

    return refusals;
  }

  // A worker that does not deliver a copy's planned output would fail the copy, so the client gets
  // the encode instead (ADR 0036). Busy workers count, so a lack of free slots refuses the session
  // for capacity and never turns the copy into an encode.
  private static boolean hasWorkerThatDeliversTheCopy(
      TranscodeDecisionRequest request, CopyPlan plan) {
    var plannedCopy =
        PlannedVideoOutput.StreamCopy.of(
            request.source().videoPlaybackFacts().orElseThrow(), plan.bitstreamRewrites());
    var copy = plan.dolbyVisionOutput().map(plannedCopy::withDolbyVisionOutput).orElse(plannedCopy);
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

  private PlanChoice acceptedVideoCopyPlan(
      TranscodeDecisionRequest request, boolean countsRefusals) {
    var source = request.source();
    var choice =
        copyPlanBeforeTheKeyframeCheck(
            source, request.capabilityDeclaration(), request.videoFormatConstraint());
    if (choice instanceof PlanChoice.Refused) {
      return choice;
    }

    var refusal = streamCopyRefusal(source);
    if (refusal.isEmpty()) {
      return choice;
    }

    if (countsRefusals) {
      countStreamCopyRefusal(refusal.get());
    }

    return new PlanChoice.Refused(List.of(refusal.get().reason()));
  }

  // The copy plan that a video capability entry accepts with why it leaves part of the source out,
  // or why the copy rules or every entry refuse each plan.
  private sealed interface PlanChoice {
    record Accepted(CopyPlan plan, List<DecisionReason> reasons) implements PlanChoice {}

    record Refused(List<DecisionReason> reasons) implements PlanChoice {}
  }

  /**
   * Whether a video capability entry for HLS accepts a copy of the source and the copy rules allow
   * it, before the keyframe check that refuses a copy whose keyframes open no segment list.
   */
  static boolean isCopyAcceptedBeforeTheKeyframeCheck(
      MediaProbe source, ClientCapabilityDeclaration declaration) {
    return copyPlanBeforeTheKeyframeCheck(source, declaration, VideoFormatConstraint.COPY_OR_ENCODE)
        instanceof PlanChoice.Accepted;
  }

  // The first copy plan that a video capability entry for HLS accepts, or the reasons that the copy
  // rules or every entry refuse each plan. Apple's HLS Authoring Specification item 1.14 requires
  // deinterlaced video, so the worker deinterlaces an interlaced source while it encodes
  // (streamarr/streamarr-server#653). A plan after the first leaves out what the entries refused in
  // the plans before it.
  private static PlanChoice copyPlanBeforeTheKeyframeCheck(
      MediaProbe source,
      ClientCapabilityDeclaration declaration,
      VideoFormatConstraint constraint) {
    if (source.videoCodec() == null) {
      return new PlanChoice.Refused(List.of(DecisionReason.VIDEO_CODEC_UNKNOWN));
    }

    var copy = VideoStreamCopy.of(source);
    var facts = source.videoPlaybackFacts();
    if (copy.isEmpty() || facts.isEmpty()) {
      return new PlanChoice.Refused(List.of(DecisionReason.VIDEO_SOURCE_NOT_COPYABLE));
    }

    if (source.isInterlaced()) {
      return new PlanChoice.Refused(List.of(DecisionReason.SOURCE_INTERLACED));
    }

    var ruleRefusal = InterimVideoCopyRule.copyRefusal(source.videoCodec(), facts.get());
    if (ruleRefusal.isPresent()) {
      return new PlanChoice.Refused(List.of(ruleRefusal.get()));
    }

    var plans =
        copyPlans(copy.get()).stream().filter(plan -> isPermittedBy(constraint, plan)).toList();
    for (var index = 0; index < plans.size(); index++) {
      var plan = plans.get(index);
      var entry = declaration.hlsCopyEntry(copy.get().afterRewrites(plan.bitstreamRewrites()));
      if (entry.isPresent()) {
        var refused = refusedBy(entry.get(), copy.get(), plans.subList(0, index));
        var leftOut = Stream.concat(refused.stream(), strippedWhateverTheClientDeclares(plan));
        return new PlanChoice.Accepted(plan, leftOut.toList());
      }
    }

    return plans.stream()
        .findFirst()
        .map(best -> copy.get().afterRewrites(best.bitstreamRewrites()))
        .map(declaration::hlsCopyRefusals)
        .map(PlanChoice.Refused::new)
        .orElse(new PlanChoice.Refused(List.of()));
  }

  // A plan that keeps Dolby Vision strips HDR10+ whatever the client declares (ADR 0036). Every
  // other plan strips HDR10+ only after an entry refused it in the plan before.
  private static Stream<DecisionReason> strippedWhateverTheClientDeclares(CopyPlan plan) {
    if (plan.dolbyVisionOutput().isEmpty()
        || !plan.bitstreamRewrites().contains(BitstreamRewrite.STRIP_HDR10_PLUS)) {
      return Stream.empty();
    }

    return Stream.of(DecisionReason.HDR10_PLUS_STRIPPED_BESIDE_DOLBY_VISION);
  }

  // What the entry that accepts a later plan refused in the plans before it: only the dynamic-range
  // information that the later plan strips, because the plans differ in nothing else.
  private static List<DecisionReason> refusedBy(
      VideoCapabilityEntry entry, VideoStreamCopy copy, List<CopyPlan> earlierPlans) {
    return earlierPlans.stream()
        .flatMap(
            plan -> entry.hlsCopyRefusals(copy.afterRewrites(plan.bitstreamRewrites())).stream())
        .distinct()
        .toList();
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

  // After a failed Dolby Vision copy, only the copy with Dolby Vision stripped may follow (ADR
  // 0052).
  private static boolean isPermittedBy(VideoFormatConstraint constraint, CopyPlan plan) {
    return constraint != VideoFormatConstraint.COPY_WITH_DOLBY_VISION_STRIPPED_OR_ENCODE
        || plan.bitstreamRewrites().contains(BitstreamRewrite.STRIP_DOLBY_VISION);
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

  // A stream copy's segments can start only at source keyframes (ADR 0037), so the video is copied
  // only when the known keyframes open its segment list. A slot without a keyframe merges into the
  // segment before it.
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
          when grid.openedAt(positions, starts).mediaSegmentCount() == 0 ->
          Optional.of(StreamCopyRefusal.NO_OPENING_KEYFRAME);
      case StreamCopySegments.Starts _ -> Optional.empty();
      case StreamCopySegments.PresentationTimeRegressed _ ->
          Optional.of(StreamCopyRefusal.PRESENTATION_TIME_REGRESSED);
      case StreamCopySegments.PresentationTimeOutOfRange _ ->
          Optional.of(StreamCopyRefusal.PRESENTATION_TIME_OUT_OF_RANGE);
      case StreamCopySegments.DecodeTimeOutOfRange _ ->
          Optional.of(StreamCopyRefusal.DECODE_TIME_OUT_OF_RANGE);
    };
  }

  private void countStreamCopyRefusal(StreamCopyRefusal refusal) {
    Counter.builder(STREAM_COPY_REFUSALS_METRIC)
        .description(
            "Stream copies of a video codec the client plays, refused because the source's"
                + " keyframes are not known or open no segment list, or a measured segment would"
                + " exceed the segment cap")
        .tag("reason", refusal.name().toLowerCase(Locale.ROOT))
        .register(meterRegistry)
        .increment();
  }

  private enum StreamCopyRefusal {
    KEYFRAMES_NOT_REPORTED(DecisionReason.KEYFRAMES_UNKNOWN),
    PACKET_SCAN_REQUIRED(DecisionReason.KEYFRAMES_UNKNOWN),
    KEYFRAMES_UNAVAILABLE(DecisionReason.KEYFRAMES_UNKNOWN),
    // No keyframe presents from zero up to the media end, so no segment opens.
    NO_OPENING_KEYFRAME(DecisionReason.KEYFRAMES_TOO_SPARSE),
    PRESENTATION_TIME_REGRESSED(DecisionReason.KEYFRAME_TIMESTAMPS_INVALID),
    PRESENTATION_TIME_OUT_OF_RANGE(DecisionReason.KEYFRAME_TIMESTAMPS_INVALID),
    DECODE_TIME_OUT_OF_RANGE(DecisionReason.KEYFRAME_TIMESTAMPS_INVALID),
    // A measured segment of the copy, with its audio, would exceed the segment cap (ADR 0037).
    EXCEEDS_SEGMENT_CAP(DecisionReason.SEGMENT_EXCEEDS_CAP);

    private final DecisionReason reason;

    StreamCopyRefusal(DecisionReason reason) {
      this.reason = reason;
    }

    DecisionReason reason() {
      return reason;
    }
  }

  private static TranscodeMode resolveTranscodeMode(
      boolean videoCopyable, AudioDecision audio, SubtitleDecision subtitle) {
    boolean subtitleForcesTranscode = subtitle.mode() == SubtitleMode.BURN_IN;
    return TranscodeMode.of(videoCopyable && !subtitleForcesTranscode, audio.mode());
  }

  private SessionAudio decideAudio(MediaProbe source, ClientCapabilityDeclaration declaration) {
    if (source.audioCodec() == null) {
      return new SessionAudio(AudioDecision.none(), Optional.empty());
    }

    // A copy carries every source channel, so the copy rule and the announced count use the
    // source's count. Only an encode names an output layout.
    var track = AudioTrackChannels.of(source);
    int sourceChannels = track.count();
    var sourceStream = source.audioStreams().stream().findFirst();
    var sourceCodecString = sourceStream.flatMap(StreamInfo::codecString);
    var copyChannelMaximum = HlsAudioEncodes.channelMaximum(declaration, source.audioCodec());
    var encodeReason =
        audioEncodeReason(copyChannelMaximum, source)
            .or(() -> audioCodecStringRefusal(source.audioCodec(), sourceCodecString))
            .or(
                () ->
                    sourceStream.flatMap(
                        stream -> audioBlockSizeRefusal(declaration, stream, sourceChannels)));
    if (encodeReason.isPresent()) {
      return new SessionAudio(HlsAudioEncodes.forTrack(declaration, track), encodeReason);
    }

    var copy =
        AudioDecision.builder()
            .mode(AudioMode.COPY)
            .codec(source.audioCodec())
            .channels(sourceChannels)
            .bitrate(
                source
                    .audioBitrate()
                    .orElse(AudioDecision.bitrateFor(source.audioCodec(), sourceChannels)))
            .sourceCodecString(sourceCodecString)
            .build();
    return new SessionAudio(copy, Optional.empty());
  }

  private static Optional<DecisionReason> audioEncodeReason(
      OptionalInt copyChannelMaximum, MediaProbe source) {
    if (copyChannelMaximum.isEmpty()) {
      return Optional.of(DecisionReason.AUDIO_CODEC_NOT_DECLARED);
    }

    if (AudioTrackChannels.of(source).count() <= copyChannelMaximum.getAsInt()) {
      return Optional.empty();
    }

    if (source.audioChannels().orElse(0) <= 0) {
      return Optional.of(DecisionReason.AUDIO_CHANNELS_UNKNOWN);
    }

    return Optional.of(DecisionReason.AUDIO_CHANNELS_NOT_DECLARED);
  }

  private static Optional<DecisionReason> audioCodecStringRefusal(
      String codec, Optional<String> sourceCodecString) {
    if (sourceCodecString.isEmpty()) {
      return Optional.of(DecisionReason.AUDIO_CODEC_STRING_UNKNOWN);
    }

    if (!isCopyableAudioCodecString(codec, sourceCodecString.get())) {
      return Optional.of(DecisionReason.AUDIO_SOURCE_NOT_COPYABLE);
    }

    return Optional.empty();
  }

  // By now an entry names the codec and accepts the channels, so only a FLAC entry's minimum block
  // size can refuse the copy (ADR 0049).
  private static Optional<DecisionReason> audioBlockSizeRefusal(
      ClientCapabilityDeclaration declaration, StreamInfo stream, int channels) {
    if (declaration.acceptsHlsAudioCopy(stream, channels)) {
      return Optional.empty();
    }

    var blockSize =
        stream
            .audioProperties()
            .map(AudioStreamProperties::flacMinimumBlockSizeSamples)
            .orElseGet(OptionalInt::empty);
    if (blockSize.isEmpty()) {
      return Optional.of(DecisionReason.AUDIO_BLOCK_SIZE_UNKNOWN);
    }

    return Optional.of(DecisionReason.AUDIO_BLOCK_SIZE_NOT_DECLARED);
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
