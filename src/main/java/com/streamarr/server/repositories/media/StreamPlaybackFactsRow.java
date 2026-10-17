package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.jooq.generated.tables.records.MediaFileStreamInfoRecord;
import java.util.OptionalInt;

// Writes the stream facts that probe version 4 adds into a stream row.
final class StreamPlaybackFactsRow {

  private StreamPlaybackFactsRow() {}

  static void set(MediaFileStreamInfoRecord row, StreamInfo stream) {
    stream.dynamicRange().ifPresent(range -> setDynamicRange(row, range));
    row.setBitDepth(nullable(stream.bitDepth()));
    row.setChromaSubsampling(stream.chromaSubsampling().map(Enum::name).orElse(null));
    row.setRotationDegrees(nullable(stream.rotationDegrees()));
    stream
        .containerCropping()
        .ifPresent(
            crop -> {
              row.setCropTop(crop.top());
              row.setCropBottom(crop.bottom());
              row.setCropLeft(crop.left());
              row.setCropRight(crop.right());
            });
    row.setCodecString(stream.codecString().orElse(null));
    row.setSampleRateHz(nullable(stream.sampleRate()));
    row.setAudioProfile(stream.audioProfile().orElse(null));
  }

  private static void setDynamicRange(MediaFileStreamInfoRecord row, VideoDynamicRange range) {
    row.setDynamicRangeCompatibleBase(range.compatibleBase().name());
    row.setHdr10Plus(range.hdr10Plus().name());
    switch (range.dolbyVision()) {
      case DolbyVision.Unspecified _ -> row.setDolbyVision(Presence.UNSPECIFIED.name());
      case DolbyVision.Absent _ -> row.setDolbyVision(Presence.ABSENT.name());
      case DolbyVision.Present(var declared, var observedRpu) -> {
        row.setDolbyVision(Presence.PRESENT.name());
        row.setDolbyVisionObservedRpu(observedRpu.name());
        declared.ifPresent(record -> setDolbyVisionRecord(row, record));
      }
    }
  }

  private static void setDolbyVisionRecord(
      MediaFileStreamInfoRecord row, DolbyVisionConfiguration record) {
    row.setDolbyVisionProfile(nullable(record.profile()));
    row.setDolbyVisionLevel(nullable(record.level()));
    row.setDolbyVisionBaseLayerCompatibilityId(nullable(record.baseLayerCompatibilityId()));
    row.setDolbyVisionBaseLayer(record.baseLayer());
    row.setDolbyVisionEnhancementLayer(record.enhancementLayer());
    row.setDolbyVisionRpu(record.rpu());
  }

  private static Integer nullable(OptionalInt value) {
    return value.isPresent() ? value.getAsInt() : null;
  }
}
