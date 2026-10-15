package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.transcode.v1.KeyframesUnavailable;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import com.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import java.util.ArrayList;
import java.util.List;

/** Maps and validates the keyframes a worker reports with a successful probe. */
final class RemoteKeyframeKnowledgeMapper {

  KeyframeKnowledge map(ProbeMediaInfo media, List<ProbeKeyframePositionsPart> parts) {
    if (!media.hasKeyframes()) {
      return new KeyframeKnowledge.NotReported();
    }

    var keyframes = media.getKeyframes();
    return switch (keyframes.getResultCase()) {
      case POSITIONS -> new KeyframeKnowledge.Known(positions(media, keyframes, parts));
      case PACKET_SCAN_REQUIRED ->
          new KeyframeKnowledge.PacketScanRequired(packetScanReason(keyframes));
      case UNAVAILABLE ->
          new KeyframeKnowledge.Unavailable(unavailableReason(keyframes.getUnavailable()));
      case RESULT_NOT_SET -> throw violation("Worker probe keyframes report no result");
    };
  }

  private KeyframePositions positions(
      ProbeMediaInfo media, ProbeKeyframes keyframes, List<ProbeKeyframePositionsPart> parts) {
    var reported = keyframes.getPositions();
    if (reported.getTimeBaseNumerator() <= 0 || reported.getTimeBaseDenominator() <= 0) {
      throw violation("Worker probe keyframe time base is not positive");
    }

    if (!isVideoStream(media, reported.getStreamIndex())) {
      throw violation("Worker probe keyframe stream index names no video stream");
    }

    var positions = absolutePositions(parts);
    if (positions.isEmpty()
        || positions.size() != Integer.toUnsignedLong(reported.getKeyframeCount())) {
      throw violation("Worker probe keyframe count differs from the keyframes it sent");
    }

    return KeyframePositions.builder()
        .streamIndex(reported.getStreamIndex())
        .timeBaseNumerator(reported.getTimeBaseNumerator())
        .timeBaseDenominator(reported.getTimeBaseDenominator())
        .source(source(reported.getSource()))
        .keyframes(positions)
        .build();
  }

  private static boolean isVideoStream(ProbeMediaInfo media, int streamIndex) {
    return media.getStreamsList().stream()
        .anyMatch(
            stream -> stream.getIndex() == streamIndex && "video".equals(stream.getCodecType()));
  }

  private static List<KeyframePosition> absolutePositions(List<ProbeKeyframePositionsPart> parts) {
    var positions = new ArrayList<KeyframePosition>();
    var presentationTime = 0L;
    var decodeTime = 0L;
    for (var part : parts) {
      if (part.getPresentationTimeDeltasCount() != part.getDecodeTimeDeltasCount()) {
        throw violation("Worker probe keyframe part lists differ in length");
      }

      for (var entry = 0; entry < part.getDecodeTimeDeltasCount(); entry++) {
        var nextDecodeTime = add(decodeTime, part.getDecodeTimeDeltas(entry));
        if (!positions.isEmpty() && nextDecodeTime <= decodeTime) {
          throw violation("Worker probe keyframe decode times do not increase");
        }

        presentationTime = add(presentationTime, part.getPresentationTimeDeltas(entry));
        decodeTime = nextDecodeTime;
        positions.add(new KeyframePosition(presentationTime, decodeTime));
      }
    }

    return positions;
  }

  private static long add(long time, long delta) {
    try {
      return Math.addExact(time, delta);
    } catch (ArithmeticException _) {
      throw violation("Worker probe keyframe time exceeds the long range");
    }
  }

  // The contract and the domain share this type name.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static KeyframePositionSource source(
      com.streamarr.transcode.v1.KeyframePositionSource source) {
    return switch (source) {
      case KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX -> KeyframePositionSource.CONTAINER_INDEX;
      case KEYFRAME_POSITION_SOURCE_PACKET_SCAN -> KeyframePositionSource.PACKET_SCAN;
      case KEYFRAME_POSITION_SOURCE_UNSPECIFIED, UNRECOGNIZED ->
          throw violation("Worker probe keyframe source is unspecified");
    };
  }

  private static KeyframePacketScanReason packetScanReason(ProbeKeyframes keyframes) {
    return switch (keyframes.getPacketScanRequired()) {
      case KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX ->
          KeyframePacketScanReason.NO_CONTAINER_INDEX;
      case KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX ->
          KeyframePacketScanReason.INCOMPLETE_CONTAINER_INDEX;
      case KEYFRAME_PACKET_SCAN_REASON_UNREADABLE_LAST_INDEXED_KEYFRAME ->
          KeyframePacketScanReason.UNREADABLE_LAST_INDEXED_KEYFRAME;
      case KEYFRAME_PACKET_SCAN_REASON_UNSPECIFIED, UNRECOGNIZED ->
          throw violation("Worker probe packet scan reason is unspecified");
    };
  }

  private static KeyframesUnavailableReason unavailableReason(KeyframesUnavailable unavailable) {
    return switch (unavailable.getReason()) {
      case KEYFRAMES_UNAVAILABLE_REASON_KEYFRAME_WITHOUT_TIMESTAMP ->
          KeyframesUnavailableReason.KEYFRAME_WITHOUT_TIMESTAMP;
      case KEYFRAMES_UNAVAILABLE_REASON_NO_KEYFRAMES -> KeyframesUnavailableReason.NO_KEYFRAMES;
      case KEYFRAMES_UNAVAILABLE_REASON_UNSPECIFIED, UNRECOGNIZED ->
          throw violation("Worker probe keyframes unavailable reason is unspecified");
    };
  }

  private static ProbeExecutionException violation(String message) {
    return new ProbeExecutionException(ItemFailureReason.TEMPORARY, message);
  }
}
