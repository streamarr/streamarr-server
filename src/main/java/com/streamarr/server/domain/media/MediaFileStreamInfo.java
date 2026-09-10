package com.streamarr.server.domain.media;

import com.streamarr.server.domain.streaming.StreamInfo;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaFileStreamInfo {

  @EmbeddedId private MediaFileStreamId id;
  private String codecType;
  private String codec;
  private Integer width;
  private Integer height;
  private Double framerate;
  private Integer channels;
  private Long bitrate;
  private String language;
  private boolean isDefault;
  private boolean isForced;

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (other == null || getClass() != other.getClass()) {
      return false;
    }

    var that = (MediaFileStreamInfo) other;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }

  public StreamInfo toStreamInfo() {
    return StreamInfo.builder()
        .index(id.streamIndex())
        .codecType(codecType)
        .codec(Optional.ofNullable(codec))
        .width(width == null ? OptionalInt.empty() : OptionalInt.of(width))
        .height(height == null ? OptionalInt.empty() : OptionalInt.of(height))
        .framerate(framerate == null ? OptionalDouble.empty() : OptionalDouble.of(framerate))
        .channels(channels == null ? OptionalInt.empty() : OptionalInt.of(channels))
        .bitrate(bitrate == null ? OptionalLong.empty() : OptionalLong.of(bitrate))
        .language(Optional.ofNullable(language))
        .isDefault(isDefault)
        .isForced(isForced)
        .build();
  }

  public static class MediaFileStreamInfoBuilder {

    public MediaFileStreamInfoBuilder stream(@NonNull UUID mediaFileId, @NonNull StreamInfo info) {
      id = new MediaFileStreamId(mediaFileId, info.index());
      codecType = info.codecType();
      codec = info.codec().orElse(null);
      width = info.width().isPresent() ? info.width().getAsInt() : null;
      height = info.height().isPresent() ? info.height().getAsInt() : null;
      framerate = info.framerate().isPresent() ? info.framerate().getAsDouble() : null;
      channels = info.channels().isPresent() ? info.channels().getAsInt() : null;
      bitrate = info.bitrate().isPresent() ? info.bitrate().getAsLong() : null;
      language = info.language().orElse(null);
      isDefault = info.isDefault();
      isForced = info.isForced();
      return this;
    }
  }
}
