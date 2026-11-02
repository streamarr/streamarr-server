package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.AudioStreamProperties;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.Optional;
import java.util.OptionalInt;

// Maps the stream facts that probe version 7 adds. The contract treats an absent disposition of an
// audio or subtitle stream as false.
final class RemoteAudioFactsMapper {

  // STREAMINFO stores the minimum block size in 16 bits.
  private static final int MAXIMUM_FLAC_BLOCK_SIZE = 65_535;

  private RemoteAudioFactsMapper() {}

  static StreamInfo.StreamInfoBuilder addAudioFacts(
      StreamInfo.StreamInfoBuilder builder, ProbeStreamInfo stream) {
    return switch (stream.getCodecType()) {
      case "audio" ->
          builder
              .comment(Optional.of(stream.getIsComment()))
              .audioProperties(Optional.of(audioProperties(stream)));
      case "subtitle" -> {
        checkNoAudioOnlyFacts(stream, "a subtitle stream");
        yield builder.comment(Optional.of(stream.getIsComment()));
      }
      default -> {
        checkNoAudioOnlyFacts(stream, "a stream that is neither audio nor subtitle");
        if (stream.hasIsComment()) {
          throw contractViolation(
              "a commentary disposition for a stream that is neither audio nor subtitle");
        }

        yield builder;
      }
    };
  }

  private static AudioStreamProperties audioProperties(ProbeStreamInfo stream) {
    return AudioStreamProperties.builder()
        .channelLayout(
            stream.hasChannelLayout() ? Optional.of(stream.getChannelLayout()) : Optional.empty())
        .sampleFormat(
            stream.hasSampleFormat() ? Optional.of(stream.getSampleFormat()) : Optional.empty())
        .visualImpaired(stream.getIsVisualImpaired())
        .descriptions(stream.getIsDescriptions())
        .original(stream.getIsOriginal())
        .dub(stream.getIsDub())
        .cleanEffects(stream.getIsCleanEffects())
        .dependent(stream.getIsDependent())
        .flacMinimumBlockSizeSamples(flacMinimumBlockSize(stream))
        .build();
  }

  private static OptionalInt flacMinimumBlockSize(ProbeStreamInfo stream) {
    if (!stream.hasFlacMinimumBlockSizeSamples()) {
      return OptionalInt.empty();
    }

    if (!"flac".equals(stream.getCodec())) {
      throw contractViolation("a FLAC minimum block size for a stream that is not FLAC");
    }

    var samples = stream.getFlacMinimumBlockSizeSamples();
    if (Integer.compareUnsigned(samples, MAXIMUM_FLAC_BLOCK_SIZE) > 0) {
      throw contractViolation("a FLAC minimum block size beyond STREAMINFO's 16 bits");
    }

    return OptionalInt.of(samples);
  }

  private static void checkNoAudioOnlyFacts(ProbeStreamInfo stream, String streamKind) {
    if (stream.hasChannelLayout()
        || stream.hasSampleFormat()
        || stream.hasIsVisualImpaired()
        || stream.hasIsDescriptions()
        || stream.hasIsOriginal()
        || stream.hasIsDub()
        || stream.hasIsCleanEffects()
        || stream.hasIsDependent()
        || stream.hasFlacMinimumBlockSizeSamples()) {
      throw contractViolation("an audio stream fact for " + streamKind);
    }
  }

  private static ProbeContractViolationException contractViolation(String fact) {
    return new ProbeContractViolationException("Worker probe reported " + fact);
  }
}
