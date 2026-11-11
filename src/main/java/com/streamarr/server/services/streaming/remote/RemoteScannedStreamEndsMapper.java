package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.DecimalSeconds;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;

/**
 * Maps and validates where a keyframe packet scan read each stream's end (ADR 0037, early ends).
 * Probe versions before 9 report none, so the mapper reads none from them.
 */
final class RemoteScannedStreamEndsMapper {

  private static final long MAXIMUM_PROTOBUF_DURATION_SECONDS = 315_576_000_000L;
  private static final int MAXIMUM_PROTOBUF_DURATION_NANOS = 999_999_999;

  List<SourceStreamEnd> map(ProbeMediaInfo media, KeyframeKnowledge keyframes, int probeVersion) {
    if (Integer.compareUnsigned(probeVersion, ProbeVersion.FIRST_TO_REPORT_SCANNED_STREAM_ENDS) < 0
        || media.getStreamEndsCount() == 0) {
      return List.of();
    }

    if (!(keyframes instanceof KeyframeKnowledge.Known(var positions))
        || positions.source() != KeyframePositionSource.PACKET_SCAN) {
      throw violation("Worker probe reports stream ends without a packet scan's keyframes");
    }

    var ends = media.getStreamEndsList().stream().map(this::streamEnd).toList();
    var indexes = new HashSet<Integer>();
    for (var end : ends) {
      if (!indexes.add(end.streamIndex())) {
        throw violation("Worker probe reports two ends for one stream");
      }

      if (!isChosenVideoOrAudio(media, positions, end.streamIndex())) {
        throw violation(
            "Worker probe stream end names neither the chosen video stream nor an audio stream");
      }

      if (end.streamIndex() == positions.streamIndex() && endsBeforeAKeyframe(end, positions)) {
        throw violation(
            "Worker probe chosen video stream ends before a keyframe's presentation time");
      }
    }

    return ends;
  }

  // The contract and the domain share this type name.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private SourceStreamEnd streamEnd(com.streamarr.transcode.v1.SourceStreamEnd reported) {
    if (!reported.hasStreamIndex() || !reported.hasEndTime()) {
      throw violation("Worker probe stream end lacks its stream index or its end time");
    }

    var end = reported.getEndTime();
    if (end.getSeconds() < 0
        || end.getSeconds() > MAXIMUM_PROTOBUF_DURATION_SECONDS
        || end.getNanos() < 0
        || end.getNanos() > MAXIMUM_PROTOBUF_DURATION_NANOS
        || (end.getSeconds() == 0 && end.getNanos() == 0)) {
      throw violation("Worker probe stream end is not a positive duration");
    }

    return new SourceStreamEnd(
        reported.getStreamIndex(), Duration.ofSeconds(end.getSeconds(), end.getNanos()));
  }

  private static boolean isChosenVideoOrAudio(
      ProbeMediaInfo media, KeyframePositions positions, int streamIndex) {
    return streamIndex == positions.streamIndex()
        || media.getStreamsList().stream()
            .anyMatch(
                stream ->
                    stream.getIndex() == streamIndex && "audio".equals(stream.getCodecType()));
  }

  // Compares without dividing by the time base, so no tick count overflows a Duration.
  private static boolean endsBeforeAKeyframe(SourceStreamEnd end, KeyframePositions positions) {
    var endTimesDenominator =
        DecimalSeconds.of(end.endTime())
            .multiply(BigDecimal.valueOf(positions.timeBaseDenominator()));
    var numerator = BigInteger.valueOf(positions.timeBaseNumerator());
    return positions.keyframes().stream()
        .map(keyframe -> BigInteger.valueOf(keyframe.presentationTime()).multiply(numerator))
        .anyMatch(
            ticksTimesNumerator ->
                endTimesDenominator.compareTo(new BigDecimal(ticksTimesNumerator)) < 0);
  }

  private static ProbeContractViolationException violation(String message) {
    return new ProbeContractViolationException(message);
  }
}
