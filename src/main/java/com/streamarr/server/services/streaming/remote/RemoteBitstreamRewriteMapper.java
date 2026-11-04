package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.BitstreamRewrite;

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
}
