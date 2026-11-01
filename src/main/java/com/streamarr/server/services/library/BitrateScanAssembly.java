package com.streamarr.server.services.library;

import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.MeasuredChunkStream;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.StoredBitrateScanChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Joins a file's stored bitrate scan chunks into the measurement of its streams (ADR 0054). Each
 * stream's interval counts follow one another in chunk order, its bytes and packets add up, and its
 * measured duration runs from its earliest first presentation time to its latest end time.
 */
final class BitrateScanAssembly {

  private BitrateScanAssembly() {}

  /**
   * The measurement, when the chunks tile the file's {@code keyframeCount} intervals exactly from
   * the first, every chunk measures the same streams, and each stream keeps one time base. Empty
   * when the chunks disagree.
   */
  static Optional<StreamMeasurements> assemble(
      List<StoredBitrateScanChunk> chunks, int keyframeCount) {
    if (chunks.isEmpty()
        || BitrateScanChunks.coveredIntervals(chunks) != keyframeCount
        || chunks.stream().mapToInt(StoredBitrateScanChunk::keyframeIntervalCount).sum()
            != keyframeCount) {
      return Optional.empty();
    }

    var first = chunks.getFirst();
    var streams = new ArrayList<MeasuredStream>();
    for (var position = 0; position < first.streams().size(); position++) {
      var streamIndex = first.streams().get(position).streamIndex();
      var parts = streamInEveryChunk(chunks, streamIndex);
      if (parts.isEmpty()) {
        return Optional.empty();
      }

      streams.add(joined(parts.get()));
    }

    if (chunks.stream().anyMatch(chunk -> chunk.streams().size() != first.streams().size())) {
      return Optional.empty();
    }

    return Optional.of(new StreamMeasurements(StreamMeasurementSource.BITRATE_SCAN, streams));
  }

  // One part of the stream from each chunk, each holding the chunk's intervals in one time base.
  private static Optional<List<MeasuredChunkStream>> streamInEveryChunk(
      List<StoredBitrateScanChunk> chunks, int streamIndex) {
    var parts = new ArrayList<MeasuredChunkStream>();
    for (var chunk : chunks) {
      var part =
          chunk.streams().stream()
              .filter(stream -> stream.streamIndex() == streamIndex)
              .findFirst();
      if (part.isEmpty()
          || part.get().keyframeIntervalBytes().intervalCount() != chunk.keyframeIntervalCount()
          || (!parts.isEmpty() && !sameTimeBase(parts.getFirst(), part.get()))) {
        return Optional.empty();
      }

      parts.add(part.get());
    }

    return Optional.of(parts);
  }

  private static boolean sameTimeBase(MeasuredChunkStream first, MeasuredChunkStream part) {
    return first.timeBaseNumerator() == part.timeBaseNumerator()
        && first.timeBaseDenominator() == part.timeBaseDenominator();
  }

  private static MeasuredStream joined(List<MeasuredChunkStream> parts) {
    var first = parts.getFirst();
    var times = parts.stream().flatMap(part -> part.packetTimes().stream()).toList();
    var duration = 0L;
    if (!times.isEmpty()) {
      var start =
          times.stream().mapToLong(MeasuredChunkStream.PacketTimes::firstPresentationTime).min();
      var end = times.stream().mapToLong(MeasuredChunkStream.PacketTimes::endTime).max();
      duration = end.orElseThrow() - start.orElseThrow();
    }

    return MeasuredStream.builder()
        .streamIndex(first.streamIndex())
        .measuredBytes(parts.stream().mapToLong(MeasuredChunkStream::measuredBytes).sum())
        .packetCount(parts.stream().mapToLong(MeasuredChunkStream::packetCount).sum())
        .timeBaseNumerator(first.timeBaseNumerator())
        .timeBaseDenominator(first.timeBaseDenominator())
        .measuredDuration(duration)
        .keyframeIntervalBytes(concatenated(parts, MeasuredChunkStream::keyframeIntervalBytes))
        .keyframeIntervalPacketCounts(
            concatenated(parts, MeasuredChunkStream::keyframeIntervalPacketCounts))
        .build();
  }

  private static KeyframeIntervalCounts concatenated(
      List<MeasuredChunkStream> parts,
      Function<MeasuredChunkStream, KeyframeIntervalCounts> counts) {
    return KeyframeIntervalCounts.ofUnsigned(
        parts.stream()
            .map(counts)
            .flatMapToInt(part -> IntStream.of(part.toUnsignedArray()))
            .toArray());
  }
}
