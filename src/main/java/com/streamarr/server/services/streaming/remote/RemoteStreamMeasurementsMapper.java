package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.MeasuredStreamOrBuilder;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import com.streamarr.transcode.v1.StreamMeasurementsOrBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Maps and validates the measurement of the streams that a worker reports with a probe. */
final class RemoteStreamMeasurementsMapper {

  Optional<StreamMeasurements> map(
      ProbeMediaInfo media, KeyframeKnowledge keyframes, List<ProbeStreamMeasurementPart> parts) {
    if (!media.hasStreamMeasurements()) {
      return Optional.empty();
    }

    if (!(keyframes instanceof KeyframeKnowledge.Known(var positions))) {
      throw violation("Worker probe measured streams without keyframe positions");
    }

    var reported = media.getStreamMeasurements();
    var source = source(reported);
    var partsByStream =
        parts.stream()
            .collect(
                Collectors.toMap(ProbeStreamMeasurementPart::getStreamIndex, Function.identity()));
    var measuredIndexes = new HashSet<Integer>();
    var streams =
        reported.getStreamsList().stream()
            .map(
                stream -> {
                  if (!measuredIndexes.add(stream.getStreamIndex())) {
                    throw violation("Worker probe measured one stream twice");
                  }

                  return measuredStream(media, stream, partsByStream, positions.keyframes().size());
                })
            .toList();
    if (!measuredIndexes.contains(positions.streamIndex())) {
      throw violation("Worker probe measurement leaves out the chosen video stream");
    }

    if (!measuredIndexes.containsAll(partsByStream.keySet())) {
      throw violation("Worker probe sent keyframe interval bytes for a stream it did not measure");
    }

    if (!measuredIndexes.containsAll(audioStreamIndexes(media))) {
      throw violation("Worker probe measurement leaves out an audio stream");
    }

    return Optional.of(new StreamMeasurements(source, streams));
  }

  private static MeasuredStream measuredStream(
      ProbeMediaInfo media,
      MeasuredStreamOrBuilder stream,
      Map<Integer, ProbeStreamMeasurementPart> partsByStream,
      int keyframeCount) {
    if (!isVideoOrAudioStream(media, stream.getStreamIndex())) {
      throw violation("Worker probe measured a stream that is neither video nor audio");
    }

    if (hasCountBeyondTheContractsRange(stream)) {
      throw violation(
          "Worker probe measured bytes, packets or duration exceed the contract's range");
    }

    if (stream.getTimeBaseNumerator() <= 0 || stream.getTimeBaseDenominator() <= 0) {
      throw violation("Worker probe measured stream time base is not positive");
    }

    var part = partsByStream.get(stream.getStreamIndex());
    if (part == null || !hasAnIntervalForEachKeyframe(part, keyframeCount)) {
      throw violation("Worker probe measured stream's intervals differ from its keyframes");
    }

    var bytes = KeyframeIntervalCounts.ofUnsigned(toArray(part.getKeyframeIntervalBytesList()));
    if (sum(bytes) != stream.getMeasuredBytes()) {
      throw violation("Worker probe measured stream's intervals differ from its measured bytes");
    }

    var packets =
        KeyframeIntervalCounts.ofUnsigned(toArray(part.getKeyframeIntervalPacketCountsList()));
    if (sum(packets) != stream.getPacketCount()) {
      throw violation("Worker probe measured stream's intervals differ from its packet count");
    }

    return MeasuredStream.builder()
        .streamIndex(stream.getStreamIndex())
        .measuredBytes(stream.getMeasuredBytes())
        .packetCount(stream.getPacketCount())
        .timeBaseNumerator(stream.getTimeBaseNumerator())
        .timeBaseDenominator(stream.getTimeBaseDenominator())
        .measuredDuration(stream.getMeasuredDuration())
        .keyframeIntervalBytes(bytes)
        .keyframeIntervalPacketCounts(packets)
        .build();
  }

  // The contract's counts are unsigned 64-bit, so a count beyond a long's range reads as negative.
  private static boolean hasCountBeyondTheContractsRange(MeasuredStreamOrBuilder stream) {
    return stream.getMeasuredBytes() < 0
        || stream.getPacketCount() < 0
        || stream.getMeasuredDuration() < 0;
  }

  private static boolean hasAnIntervalForEachKeyframe(
      ProbeStreamMeasurementPart part, int keyframeCount) {
    return part.getKeyframeIntervalBytesCount() == keyframeCount
        && part.getKeyframeIntervalPacketCountsCount() == keyframeCount;
  }

  private static int[] toArray(List<Integer> unsignedCounts) {
    return unsignedCounts.stream().mapToInt(Integer::intValue).toArray();
  }

  private static long sum(KeyframeIntervalCounts counts) {
    var total = 0L;
    for (var interval = 0; interval < counts.intervalCount(); interval++) {
      total += counts.countOf(interval);
    }

    return total;
  }

  private static Set<Integer> audioStreamIndexes(ProbeMediaInfo media) {
    return media.getStreamsList().stream()
        .filter(stream -> "audio".equals(stream.getCodecType()))
        .map(ProbeStreamInfo::getIndex)
        .collect(Collectors.toSet());
  }

  private static boolean isVideoOrAudioStream(ProbeMediaInfo media, int streamIndex) {
    return media.getStreamsList().stream()
        .anyMatch(
            stream ->
                stream.getIndex() == streamIndex
                    && ("video".equals(stream.getCodecType())
                        || "audio".equals(stream.getCodecType())));
  }

  private static StreamMeasurementSource source(StreamMeasurementsOrBuilder reported) {
    return switch (reported.getSource()) {
      case STREAM_MEASUREMENT_SOURCE_SAMPLE_TABLE -> StreamMeasurementSource.SAMPLE_TABLE;
      case STREAM_MEASUREMENT_SOURCE_KEYFRAME_PACKET_SCAN ->
          StreamMeasurementSource.KEYFRAME_PACKET_SCAN;
      case STREAM_MEASUREMENT_SOURCE_BITRATE_SCAN -> StreamMeasurementSource.BITRATE_SCAN;
      case STREAM_MEASUREMENT_SOURCE_UNSPECIFIED, UNRECOGNIZED ->
          throw violation("Worker probe measurement names no source");
    };
  }

  private static ProbeContractViolationException violation(String message) {
    return new ProbeContractViolationException(message);
  }
}
