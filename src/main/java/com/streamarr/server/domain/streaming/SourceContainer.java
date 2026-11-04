package com.streamarr.server.domain.streaming;

import java.util.Map;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The container of a media file as the probe found it. Direct play serves the file with this
 * container's media type, never one guessed from the file name.
 */
@Getter
@RequiredArgsConstructor
public enum SourceContainer {
  /** MPEG-4 Part 14, including M4V and 3GP. */
  MP4("mp4", "video/mp4"),
  /** The QuickTime file format. */
  MOV("mov", "video/quicktime"),
  /** Matroska, including WebM. */
  MATROSKA("mkv", "video/x-matroska"),
  /** The MPEG-2 transport stream, including M2TS. */
  MPEG_TS("ts", "video/mp2t"),
  /** The MPEG-2 program stream, as in MPG and VOB files. */
  MPEG_PS("mpg", "video/mpeg"),
  /** Audio Video Interleave. */
  AVI("avi", "video/x-msvideo"),
  /** The Advanced Systems Format, as in WMV files. */
  ASF("asf", "video/x-ms-asf"),
  /** Flash Video. */
  FLV("flv", "video/x-flv");

  /** The extension of the direct-play URL, which players use to pick a parser. */
  private final String extension;

  private final String mediaType;

  // ffprobe's demuxer names. The mov demuxer reads MP4 and QuickTime files alike, and the file's
  // major brand tells them apart.
  private static final String MOV_DEMUXER = "mov,mp4,m4a,3gp,3g2,mj2";
  private static final String QUICKTIME_MAJOR_BRAND = "qt  ";
  private static final Map<String, SourceContainer> BY_DEMUXER =
      Map.of(
          "matroska,webm", MATROSKA,
          "mpegts", MPEG_TS,
          "mpeg", MPEG_PS,
          "avi", AVI,
          "asf", ASF,
          "flv", FLV);

  /** Whether the container stores a sample entry for each video stream, as MP4 and MOV do. */
  public boolean storesSampleEntries() {
    return this == MP4 || this == MOV;
  }

  /**
   * The source container that ffprobe's demuxer name names, or empty when the name names no video
   * container. For the demuxer of MP4 and MOV files, QuickTime's major brand, {@code qt} and two
   * spaces, names MOV, and every other major brand names MP4, among them the M4V and 3GP brands. A
   * file without a major brand, which ISO/IEC 14496-12 reads as {@code mp41}, also counts as MP4
   * (ADR 0045).
   */
  public static Optional<SourceContainer> ofProbedFormat(
      @NonNull String demuxer, @NonNull Optional<String> majorBrand) {
    if (!MOV_DEMUXER.equals(demuxer)) {
      return Optional.ofNullable(BY_DEMUXER.get(demuxer));
    }

    if (majorBrand.filter(QUICKTIME_MAJOR_BRAND::equals).isPresent()) {
      return Optional.of(MOV);
    }

    return Optional.of(MP4);
  }
}
