package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.BitstreamRewrite;
import java.util.Optional;

// Maps the bitstream rewrites of ADR 0036 between the domain and the worker contract. Domain and
// protobuf contracts intentionally share simple names.
final class RemoteBitstreamRewriteMapper {

  private RemoteBitstreamRewriteMapper() {}

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static com.streamarr.transcode.v1.BitstreamRewrite toProto(BitstreamRewrite rewrite) {
    return switch (rewrite) {
      case STRIP_DOLBY_VISION ->
          com.streamarr.transcode.v1.BitstreamRewrite.BITSTREAM_REWRITE_STRIP_DOLBY_VISION;
      case STRIP_HDR10_PLUS ->
          com.streamarr.transcode.v1.BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS;
      case NORMALIZE_DOLBY_VISION_RECORD ->
          com.streamarr.transcode.v1.BitstreamRewrite
              .BITSTREAM_REWRITE_NORMALIZE_DOLBY_VISION_RECORD;
    };
  }

  /** The domain rewrite of a contract value, or empty for one that the domain does not name. */
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Optional<BitstreamRewrite> fromProto(int value) {
    return switch (com.streamarr.transcode.v1.BitstreamRewrite.forNumber(value)) {
      case BITSTREAM_REWRITE_STRIP_DOLBY_VISION -> Optional.of(BitstreamRewrite.STRIP_DOLBY_VISION);
      case BITSTREAM_REWRITE_STRIP_HDR10_PLUS -> Optional.of(BitstreamRewrite.STRIP_HDR10_PLUS);
      case BITSTREAM_REWRITE_NORMALIZE_DOLBY_VISION_RECORD ->
          Optional.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD);
      case BITSTREAM_REWRITE_CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
          BITSTREAM_REWRITE_UNSPECIFIED,
          UNRECOGNIZED ->
          Optional.empty();
      case null -> Optional.empty();
    };
  }
}
