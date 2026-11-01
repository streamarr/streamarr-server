package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.BitrateScanChunkOutcome;
import com.streamarr.server.domain.streaming.BitrateScanChunkRequest;
import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.MeasuredChunkStream;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.BitrateScanChunk;
import com.streamarr.transcode.v1.BitrateScanChunkResult;
import com.streamarr.transcode.v1.BitrateScanChunkStream;
import com.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds a bitrate scan chunk for a worker, and maps and validates the worker's result. */
final class RemoteBitrateScanChunkMapper {

  BitrateScanChunk chunk(BitrateScanChunkRequest request) {
    var chunk =
        BitrateScanChunk.newBuilder()
            .setMeasurementVersion(request.measurementVersion())
            .setChosenVideoStreamIndex(request.chosenVideoStreamIndex())
            .addAllMeasuredStreamIndexes(request.measuredStreamIndexes())
            .setTimeBaseNumerator(request.timeBaseNumerator())
            .setTimeBaseDenominator(request.timeBaseDenominator())
            .setFirstChunk(request.firstChunk());
    var previous = new KeyframePosition(0, 0);
    for (var keyframe : request.keyframes()) {
      chunk
          .addPresentationTimeDeltas(keyframe.presentationTime() - previous.presentationTime())
          .addDecodeTimeDeltas(keyframe.decodeTime() - previous.decodeTime());
      previous = keyframe;
    }

    request
        .end()
        .ifPresent(
            end ->
                chunk
                    .setEndPresentationTime(end.presentationTime())
                    .setEndDecodeTime(end.decodeTime()));
    return chunk.build();
  }

  BitrateScanChunkOutcome map(BitrateScanChunk chunk, ProbeAttemptReport report) {
    var result = report.result();
    return switch (result.getOutcomeCase()) {
      case BITRATE_SCAN_CHUNK ->
          chunkResult(chunk, result.getBitrateScanChunk(), report.streamMeasurementParts());
      case MEDIA -> throw violation("Worker answered a bitrate scan chunk with a probe result");
      case FAILURE, OUTCOME_NOT_SET ->
          new BitrateScanChunkOutcome.MediaError(RemoteProbeFailureMapper.terminalError(result));
    };
  }

  private static BitrateScanChunkOutcome chunkResult(
      BitrateScanChunk chunk,
      BitrateScanChunkResult result,
      List<ProbeStreamMeasurementPart> parts) {
    return switch (result.getResultCase()) {
      case MEASURED -> measured(chunk, result, parts);
      case COPY_CHANGES_PACKETS -> copyChangesPackets(chunk, result);
      case RESULT_NOT_SET -> throw violation("Worker bitrate scan chunk result reports no result");
    };
  }

  private static BitrateScanChunkOutcome copyChangesPackets(
      BitrateScanChunk chunk, BitrateScanChunkResult result) {
    var streamIndex = result.getCopyChangesPackets().getStreamIndex();
    if (!chunk.getMeasuredStreamIndexesList().contains(streamIndex)) {
      throw violation("Worker bitrate scan chunk names a stream it was not asked to measure");
    }

    return new BitrateScanChunkOutcome.CopyChangesPackets(streamIndex);
  }

  private static BitrateScanChunkOutcome measured(
      BitrateScanChunk chunk,
      BitrateScanChunkResult result,
      List<ProbeStreamMeasurementPart> parts) {
    var requested = Set.copyOf(chunk.getMeasuredStreamIndexesList());
    var partsByStream =
        parts.stream()
            .collect(
                Collectors.toMap(ProbeStreamMeasurementPart::getStreamIndex, Function.identity()));
    var measuredIndexes = new HashSet<Integer>();
    var streams =
        result.getMeasured().getStreamsList().stream()
            .map(
                stream -> {
                  if (!requested.contains(stream.getStreamIndex())) {
                    throw violation(
                        "Worker bitrate scan chunk names a stream it was not asked to measure");
                  }

                  if (!measuredIndexes.add(stream.getStreamIndex())) {
                    throw violation("Worker bitrate scan chunk measured one stream twice");
                  }

                  return measuredStream(stream, partsByStream, chunk.getDecodeTimeDeltasCount());
                })
            .toList();
    if (!measuredIndexes.equals(requested)) {
      throw violation("Worker bitrate scan chunk leaves out a stream it was asked to measure");
    }

    if (!measuredIndexes.containsAll(partsByStream.keySet())) {
      throw violation(
          "Worker bitrate scan chunk sent keyframe interval bytes for a stream it did not measure");
    }

    return new BitrateScanChunkOutcome.Measured(streams);
  }

  private static MeasuredChunkStream measuredStream(
      BitrateScanChunkStream stream,
      Map<Integer, ProbeStreamMeasurementPart> partsByStream,
      int keyframeCount) {
    if (stream.getMeasuredBytes() < 0 || stream.getPacketCount() < 0) {
      throw violation("Worker bitrate scan chunk bytes or packets exceed the contract's range");
    }

    if (stream.getTimeBaseNumerator() <= 0 || stream.getTimeBaseDenominator() <= 0) {
      throw violation("Worker bitrate scan chunk stream time base is not positive");
    }

    var part = partsByStream.get(stream.getStreamIndex());
    if (part == null
        || part.getKeyframeIntervalBytesCount() != keyframeCount
        || part.getKeyframeIntervalPacketCountsCount() != keyframeCount) {
      throw violation("Worker bitrate scan chunk stream's intervals differ from the chunk's");
    }

    var bytes = KeyframeIntervalCounts.ofUnsigned(toArray(part.getKeyframeIntervalBytesList()));
    var packets =
        KeyframeIntervalCounts.ofUnsigned(toArray(part.getKeyframeIntervalPacketCountsList()));
    if (bytes.total() != stream.getMeasuredBytes() || packets.total() != stream.getPacketCount()) {
      throw violation("Worker bitrate scan chunk stream's intervals differ from its totals");
    }

    return MeasuredChunkStream.builder()
        .streamIndex(stream.getStreamIndex())
        .measuredBytes(stream.getMeasuredBytes())
        .packetCount(stream.getPacketCount())
        .timeBaseNumerator(stream.getTimeBaseNumerator())
        .timeBaseDenominator(stream.getTimeBaseDenominator())
        .packetTimes(packetTimes(stream))
        .keyframeIntervalBytes(bytes)
        .keyframeIntervalPacketCounts(packets)
        .build();
  }

  private static Optional<MeasuredChunkStream.PacketTimes> packetTimes(
      BitrateScanChunkStream stream) {
    var holdsPackets = stream.getPacketCount() > 0;
    if (stream.hasFirstPresentationTime() != holdsPackets || stream.hasEndTime() != holdsPackets) {
      throw violation(
          "Worker bitrate scan chunk stream's times must be present exactly when it holds packets");
    }

    if (!holdsPackets) {
      return Optional.empty();
    }

    if (stream.getEndTime() < stream.getFirstPresentationTime()) {
      throw violation("Worker bitrate scan chunk stream ends before its first presentation time");
    }

    return Optional.of(
        new MeasuredChunkStream.PacketTimes(
            stream.getFirstPresentationTime(), stream.getEndTime()));
  }

  private static int[] toArray(List<Integer> unsignedCounts) {
    return unsignedCounts.stream().mapToInt(Integer::intValue).toArray();
  }

  private static ProbeContractViolationException violation(String message) {
    return new ProbeContractViolationException(message);
  }
}
