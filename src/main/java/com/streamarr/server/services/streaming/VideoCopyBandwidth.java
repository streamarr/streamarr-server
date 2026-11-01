package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * What a video copy costs beside the stream session's audio: the AVERAGE-BANDWIDTH that the
 * multivariant playlist advertises for the copy and the cost that the viewer's maximum bitrate
 * bounds (ADR 0050, ADR 0054).
 */
final class VideoCopyBandwidth {

  // A copy's media segment is one moof and one mdat (worker_session.proto, MeasuredStream): the
  // moof header, the mfhd and the mdat header; then for each track a traf header, a tfhd, a
  // version 1 tfdt and the trun header; and a trun entry for each sample.
  private static final long SEGMENT_FRAMING_BYTES = 32;
  private static final long TRACK_FRAMING_BYTES = 64;
  private static final long SAMPLE_FRAMING_BYTES = 16;

  private static final List<Integer> AAC_SAMPLE_RATES =
      List.of(
          7_350, 8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000, 64_000,
          88_200, 96_000);
  private static final List<Integer> AC3_SAMPLE_RATES = List.of(32_000, 44_100, 48_000);

  /** The FFmpeg audio encoders that a session runs, by the codec that they encode. */
  private static final Map<String, AudioEncoder> AUDIO_ENCODERS =
      Map.of(
          "aac", new AudioEncoder(1_024, 1_024, AAC_SAMPLE_RATES),
          "ac3", new AudioEncoder(1_536, 256, AC3_SAMPLE_RATES),
          "eac3", new AudioEncoder(1_536, 256, AC3_SAMPLE_RATES));

  private VideoCopyBandwidth() {}

  /**
   * The copied video's bitrate plus the stream session's audio bitrate, counted so that it never
   * falls below what the player downloads. When the server holds a measurement of the source's
   * streams and a stream copy lists the media playlist, the copy costs every byte that its media
   * segments deliver over the sum of the playlist's EXTINF durations: the measured video bytes, a
   * copied audio track's measured bytes, and the fragment framing of each segment, track and
   * sample. Encoded audio adds its bitrate and the framing of the frames that its encoder writes.
   *
   * <p>Without a measurement, the probed bitrate of the copied video counts. When the probe knows
   * no bitrate for the copied video, as for Matroska, the container bitrate stands in for it. That
   * rate already holds every source track, so a copied audio track adds nothing to it, while
   * encoded audio adds its bitrate on top. A copied audio track whose bitrate the probe does not
   * know takes a nominal bitrate, which can be far below a lossless track's rate, so a known
   * container bitrate, which holds that track, bounds the copy from below.
   */
  static long averageBandwidth(
      @NonNull MediaProbe source,
      @NonNull AudioDecision audio,
      @NonNull CopyMediaPlaylist playlist) {
    return measuredCost(source, audio, playlist).orElseGet(() -> unmeasuredCost(source, audio));
  }

  private static long unmeasuredCost(MediaProbe source, AudioDecision audio) {
    var copiesAudio = audio.mode() == AudioMode.COPY;
    var videoBitrate = copiedStreamBitrate(source);
    if (videoBitrate.isEmpty() && copiesAudio) {
      return Math.max(source.bitrate(), audio.bitrate());
    }

    var averageBandwidth = videoBitrate.orElse(source.bitrate()) + audio.bitrate();
    if (copiesAudio && source.audioBitrate().isEmpty()) {
      return Math.max(averageBandwidth, source.bitrate());
    }

    return averageBandwidth;
  }

  /**
   * Whether the server measured the copied video over a media playlist that a stream copy lists, or
   * the probe knows its bitrate or the container bitrate.
   */
  static boolean isKnown(@NonNull MediaProbe source, @NonNull CopyMediaPlaylist playlist) {
    return (playlist.isKnown() && measuredVideo(source).isPresent())
        || copiedStreamBitrate(source).isPresent()
        || source.bitrate() > 0;
  }

  /**
   * The bytes that the copy's media segments deliver over the playlist's duration, plus encoded
   * audio's bitrate, or empty when the server did not measure the copied video or a copied audio
   * track, when the encoded audio's framing is not known, or when no stream copy lists the media
   * playlist. The copy carries the source's first audio stream.
   */
  private static Optional<Long> measuredCost(
      MediaProbe source, AudioDecision audio, CopyMediaPlaylist playlist) {
    var video = measuredVideo(source);
    if (video.isEmpty() || !playlist.isKnown()) {
      return Optional.empty();
    }

    var segments = BigInteger.valueOf(playlist.segmentCount());
    var videoBytes =
        segments
            .multiply(BigInteger.valueOf(SEGMENT_FRAMING_BYTES))
            .add(trackBytes(video.get(), segments));
    return switch (audio.mode()) {
      case NONE -> Optional.of(averageBitRate(videoBytes, playlist));
      case COPY ->
          copiedAudio(source)
              .map(
                  copied -> averageBitRate(videoBytes.add(trackBytes(copied, segments)), playlist));
      case TRANSCODE ->
          encodedAudioFrames(source, audio, playlist)
              .map(
                  frames ->
                      averageBitRate(videoBytes.add(framingBytes(segments, frames)), playlist))
              .map(bitRate -> saturatedSum(bitRate, audio.bitrate()));
    };
  }

  private static Optional<MeasuredStream> measuredVideo(MediaProbe source) {
    return copiedStreamIndex(source)
        .flatMap(index -> measuredStream(source, index))
        .filter(stream -> stream.measuredBytes() > 0);
  }

  private static Optional<MeasuredStream> copiedAudio(MediaProbe source) {
    return source.audioStreams().stream()
        .findFirst()
        .flatMap(stream -> measuredStream(source, stream.index()));
  }

  private static Optional<MeasuredStream> measuredStream(MediaProbe source, int streamIndex) {
    return source.streamMeasurements().flatMap(measurement -> measurement.stream(streamIndex));
  }

  /** A track's measured bytes and its framing in every segment. */
  private static BigInteger trackBytes(MeasuredStream stream, BigInteger segments) {
    return BigInteger.valueOf(stream.measuredBytes())
        .add(framingBytes(segments, new BigInteger(Long.toUnsignedString(stream.packetCount()))));
  }

  private static BigInteger framingBytes(BigInteger segments, BigInteger samples) {
    return segments
        .multiply(BigInteger.valueOf(TRACK_FRAMING_BYTES))
        .add(samples.multiply(BigInteger.valueOf(SAMPLE_FRAMING_BYTES)));
  }

  /**
   * The frames that the session's audio encoder writes over the playlist's duration, with its
   * priming samples, at the lowest rate that it takes at or above the source's first audio stream's
   * rate, or at its highest rate when that rate is higher or not known.
   */
  private static Optional<BigInteger> encodedAudioFrames(
      MediaProbe source, AudioDecision audio, CopyMediaPlaylist playlist) {
    var encoder = Optional.ofNullable(audio.codec()).map(AUDIO_ENCODERS::get);
    if (encoder.isEmpty()) {
      return Optional.empty();
    }

    var rate = encoder.get().highestRate();
    var sourceRate =
        source.audioStreams().stream()
            .findFirst()
            .map(StreamInfo::sampleRate)
            .orElse(OptionalInt.empty());
    if (sourceRate.isPresent()) {
      rate = encoder.get().rateAtOrAbove(sourceRate.getAsInt());
    }

    var samples =
        playlist
            .durationSeconds()
            .multiply(BigDecimal.valueOf(rate))
            .add(BigDecimal.valueOf(encoder.get().primingSamples()));
    return Optional.of(
        samples
            .divide(BigDecimal.valueOf(encoder.get().frameSamples()), 0, RoundingMode.CEILING)
            .toBigIntegerExact());
  }

  /** The bytes in bits per second of the playlist's EXTINF durations, rounded up. */
  private static long averageBitRate(BigInteger bytes, CopyMediaPlaylist playlist) {
    var bitRate =
        new BigDecimal(bytes.shiftLeft(3))
            .divide(playlist.durationSeconds(), 0, RoundingMode.CEILING)
            .toBigIntegerExact();
    return bitRate.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
  }

  private static long saturatedSum(long first, long second) {
    return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
  }

  private static Optional<Integer> copiedStreamIndex(MediaProbe source) {
    return source.videoPlaybackFacts().map(VideoPlaybackFacts::streamIndex);
  }

  private static OptionalLong copiedStreamBitrate(MediaProbe source) {
    var streamIndex = copiedStreamIndex(source);
    if (streamIndex.isEmpty()) {
      return OptionalLong.empty();
    }

    return source.streams().stream()
        .filter(stream -> stream.index() == streamIndex.get())
        .flatMapToLong(stream -> stream.bitrate().stream())
        .findFirst();
  }

  /**
   * An audio encoder that writes frames of {@code frameSamples} after {@code primingSamples} of
   * priming, at one of {@code sampleRates} in ascending order.
   */
  private record AudioEncoder(long frameSamples, long primingSamples, List<Integer> sampleRates) {

    int highestRate() {
      return sampleRates.getLast();
    }

    /** The lowest rate at or above the source's, or the highest rate below it. */
    int rateAtOrAbove(int sourceRate) {
      return sampleRates.stream()
          .filter(rate -> rate >= sourceRate)
          .findFirst()
          .orElseGet(this::highestRate);
    }
  }
}
