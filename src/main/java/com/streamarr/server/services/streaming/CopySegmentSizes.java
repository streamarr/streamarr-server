package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.services.streaming.PeakSegmentBitRate.SegmentSize;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The bytes that each media segment of a measured stream copy delivers for one track: the track's
 * measured bytes and samples in the segment's keyframe intervals, plus the segment's framing (ADR
 * 0054).
 */
final class CopySegmentSizes {

  // ADR 0054: an encoded audio rendition's peak is 1.2 × its bitrate, the headroom that ADR 0044
  // gives every encoded rate.
  private static final BigDecimal ENCODED_AUDIO_PEAK_HEADROOM = new BigDecimal("1.2");

  private CopySegmentSizes() {}

  /**
   * Each segment's copied video: the keyframe intervals from its opening keyframe to the next
   * segment's, with the segment's own framing and the video track's.
   */
  static List<SegmentSize> video(MeasuredStream video, CopyMediaPlaylist playlist) {
    var keyframeCount = video.keyframeIntervalBytes().intervalCount();
    var sizes = new ArrayList<SegmentSize>();
    for (var segment = 0; segment < playlist.segmentCount(); segment++) {
      var intervals = videoIntervals(playlist, segment, keyframeCount);
      var bytes =
          SegmentFraming.segmentBytes(BigInteger.ONE)
              .add(trackBytes(video, intervals.first(), intervals.end()));
      sizes.add(sizeOf(bytes, playlist, segment));
    }

    return sizes;
  }

  /**
   * Each segment's copied audio. The worker puts an audio sample in the segment whose grid slot
   * holds the sample's start: segment 0 also takes the audio before zero, and the last segment the
   * audio after its slot (ADR 0037). The copy can snap a sample across a slot's edge, so a
   * segment's audio is bounded by every keyframe interval that overlaps its slot widened by one
   * audio frame on each side. Keyframe interval {@code i} holds the audio that presents from
   * keyframe {@code i} to keyframe {@code i + 1}; the first is open below and the last above. The
   * audio track's framing counts in every segment, even one without an audio sample.
   */
  static List<SegmentSize> copiedAudio(
      MeasuredStream audio, KeyframePositions positions, CopyMediaPlaylist playlist) {
    var intervals = new AudioIntervals(positions, audioFrameTicks(audio, positions));
    var lastSegment = playlist.segmentCount() - 1;
    var first = 0;
    var end = 1;
    var sizes = new ArrayList<SegmentSize>();
    for (var segment = 0; segment <= lastSegment; segment++) {
      if (segment > 0) {
        first = intervals.firstEndingAfter(slotEdge(positions, playlist, segment), first);
      }

      end =
          segment == lastSegment
              ? positions.keyframes().size()
              : intervals.endStartingBefore(slotEdge(positions, playlist, segment + 1), end);

      sizes.add(sizeOf(trackBytes(audio, first, end), playlist, segment));
    }

    return sizes;
  }

  /**
   * Each segment's encoded audio, which the worker takes from the segment's grid slot as it does
   * copied audio. The segment holds the frames that the encoder writes over the slot's audio, with
   * its priming samples in segment 0, plus one frame that the snap can move across an edge. Each
   * frame carries 1.2 × the encoded audio's bitrate over its length (ADR 0054) and its share of the
   * audio track's framing.
   */
  static List<SegmentSize> encodedAudio(
      EncodedAudioFrames encoded, long bitrate, CopyMediaPlaylist playlist) {
    var peakBytesPerSecond =
        BigDecimal.valueOf(bitrate)
            .multiply(ENCODED_AUDIO_PEAK_HEADROOM)
            .divide(BigDecimal.valueOf(Byte.SIZE));
    var sizes = new ArrayList<SegmentSize>();
    for (var segment = 0; segment < playlist.segmentCount(); segment++) {
      var audioSeconds = slotAudioSeconds(playlist, segment);
      var frames = encoded.over(audioSeconds, segment == 0).add(BigInteger.ONE);
      var framing = SegmentFraming.trackBytes(BigInteger.ONE, frames);
      var payload = peakBytesPerSecond.multiply(encoded.secondsOf(frames));
      var bytes = payload.add(new BigDecimal(framing));
      sizes.add(new SegmentSize(bytes, playlist.segments().get(segment).extinfSeconds()));
    }

    return sizes;
  }

  /**
   * Segment {@code N} holds the keyframe intervals from its opening keyframe up to the next
   * segment's. Segment 0 also holds the intervals before its opening keyframe, whose keyframes
   * present before zero, and the last segment also holds the intervals of the keyframes that
   * present at or after the media end, which open no segment.
   */
  private static Intervals videoIntervals(
      CopyMediaPlaylist playlist, int segment, int keyframeCount) {
    var segments = playlist.segments();
    var first = 0;
    if (segment > 0) {
      first = segments.get(segment).openingKeyframeIndex();
    }

    var end = keyframeCount;
    if (segment + 1 < segments.size()) {
      end = segments.get(segment + 1).openingKeyframeIndex();
    }

    return new Intervals(first, end);
  }

  /**
   * One audio frame in ticks of the keyframe positions' time base: the audio stream's average
   * packet duration, rounded up. Zero for a stream without packets.
   */
  private static BigInteger audioFrameTicks(MeasuredStream audio, KeyframePositions positions) {
    if (audio.packetCount() == 0) {
      return BigInteger.ZERO;
    }

    var audioTicksTimesBases =
        BigInteger.valueOf(audio.measuredDuration())
            .multiply(BigInteger.valueOf(audio.timeBaseNumerator()))
            .multiply(BigInteger.valueOf(positions.timeBaseDenominator()));
    var packetsTimesBases =
        BigInteger.valueOf(audio.packetCount())
            .multiply(BigInteger.valueOf(audio.timeBaseDenominator()))
            .multiply(BigInteger.valueOf(positions.timeBaseNumerator()));
    return new BigDecimal(audioTicksTimesBases)
        .divide(new BigDecimal(packetsTimesBases), 0, RoundingMode.CEILING)
        .toBigIntegerExact();
  }

  /**
   * The seconds of audio in a segment's grid slots: up to the next segment's slot start, or for the
   * last segment the rest of the playlist from its slot start.
   */
  private static BigDecimal slotAudioSeconds(CopyMediaPlaylist playlist, int segment) {
    var segments = playlist.segments();
    var slotStart = segments.get(segment).slotStartSeconds();
    if (segment + 1 < segments.size()) {
      return BigDecimal.valueOf(segments.get(segment + 1).slotStartSeconds() - slotStart);
    }

    return playlist.durationSeconds().subtract(BigDecimal.valueOf(slotStart));
  }

  /**
   * Where the grid slots of segment {@code segment} start, in ticks of the keyframe positions' time
   * base times their numerator: the slot start in seconds times their denominator.
   */
  private static BigInteger slotEdge(
      KeyframePositions positions, CopyMediaPlaylist playlist, int segment) {
    return BigInteger.valueOf(playlist.segments().get(segment).slotStartSeconds())
        .multiply(BigInteger.valueOf(positions.timeBaseDenominator()));
  }

  /**
   * The keyframe intervals that a copied audio track's samples can fill, with one audio frame of
   * slack on each side. Interval {@code i} runs from keyframe {@code i}'s presentation time to the
   * next keyframe's; the first is open below and the last above. Times count ticks of the keyframe
   * positions' time base times their numerator, as slot edges do.
   */
  private record AudioIntervals(KeyframePositions positions, BigInteger frameTicks) {

    /** The first interval from {@code from} on that ends more than one frame after the edge. */
    int firstEndingAfter(BigInteger edge, int from) {
      var first = from;
      while (first < lastInterval() && scaledTime(first + 1, frameTicks).compareTo(edge) <= 0) {
        first++;
      }

      return first;
    }

    /**
     * The end of the intervals from {@code from} on that start less than one frame past the edge.
     */
    int endStartingBefore(BigInteger edge, int from) {
      var end = from;
      while (end <= lastInterval() && scaledTime(end, frameTicks.negate()).compareTo(edge) < 0) {
        end++;
      }

      return end;
    }

    private int lastInterval() {
      return positions.keyframes().size() - 1;
    }

    private BigInteger scaledTime(int keyframe, BigInteger offset) {
      return BigInteger.valueOf(positions.keyframes().get(keyframe).presentationTime())
          .add(offset)
          .multiply(BigInteger.valueOf(positions.timeBaseNumerator()));
    }
  }

  /** A track's measured bytes in intervals {@code [first, end)} and its framing in one segment. */
  private static BigInteger trackBytes(MeasuredStream stream, int first, int end) {
    var bytes = 0L;
    var samples = 0L;
    for (var interval = first; interval < end; interval++) {
      bytes += stream.keyframeIntervalBytes().countOf(interval);
      samples += stream.keyframeIntervalPacketCounts().countOf(interval);
    }

    return BigInteger.valueOf(bytes)
        .add(SegmentFraming.trackBytes(BigInteger.ONE, BigInteger.valueOf(samples)));
  }

  private static SegmentSize sizeOf(BigInteger bytes, CopyMediaPlaylist playlist, int segment) {
    return new SegmentSize(new BigDecimal(bytes), playlist.segments().get(segment).extinfSeconds());
  }

  private record Intervals(int first, int end) {}
}
