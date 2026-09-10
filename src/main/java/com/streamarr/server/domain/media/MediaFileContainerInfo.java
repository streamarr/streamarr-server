package com.streamarr.server.domain.media;

import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaFileContainerInfo {

  @Id
  @Column(name = "media_file_id")
  private UUID mediaFileId;

  @Getter(AccessLevel.NONE)
  private long sourceSize;

  @Getter(AccessLevel.NONE)
  private long sourceModifiedEpochSecond;

  @Getter(AccessLevel.NONE)
  private int sourceModifiedNanos;

  private int probeVersion;

  @Getter(AccessLevel.NONE)
  private String format;

  @Getter(AccessLevel.NONE)
  private Long durationSeconds;

  @Getter(AccessLevel.NONE)
  private Integer durationNanos;

  @Getter(AccessLevel.NONE)
  private Long totalBitrate;

  @Getter(AccessLevel.NONE)
  @Enumerated(EnumType.STRING)
  private ProbeError probeError;

  @Getter(AccessLevel.NONE)
  @OneToMany
  @JoinColumn(name = "media_file_id", insertable = false, updatable = false)
  @OrderBy("id.streamIndex ASC")
  @Builder.Default
  private List<MediaFileStreamInfo> streams = new ArrayList<>();

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (other == null || getClass() != other.getClass()) {
      return false;
    }

    var that = (MediaFileContainerInfo) other;
    return mediaFileId != null && mediaFileId.equals(that.getMediaFileId());
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }

  public ProbeOutcome getOutcome() {
    if (probeError != null) {
      return new ProbeOutcome.Failure(probeError);
    }

    return new ProbeOutcome.Success(
        getContainer(), streams.stream().map(MediaFileStreamInfo::toStreamInfo).toList());
  }

  public Optional<ProbeError> getProbeError() {
    return Optional.ofNullable(probeError);
  }

  public ProbeContainer getContainer() {
    return ProbeContainer.builder()
        .format(Optional.ofNullable(format))
        .duration(
            Optional.ofNullable(durationSeconds)
                .map(seconds -> Duration.ofSeconds(seconds, durationNanos)))
        .bitrate(totalBitrate == null ? OptionalLong.empty() : OptionalLong.of(totalBitrate))
        .build();
  }

  public SourceFileSnapshot getSnapshot() {
    return new SourceFileSnapshot(
        sourceSize, Instant.ofEpochSecond(sourceModifiedEpochSecond, sourceModifiedNanos));
  }

  public static class MediaFileContainerInfoBuilder {

    public MediaFileContainerInfoBuilder container(ProbeContainer container) {
      format = container.format().orElse(null);
      durationSeconds = container.duration().map(Duration::getSeconds).orElse(null);
      durationNanos = container.duration().map(Duration::getNano).orElse(null);
      totalBitrate = container.bitrate().isPresent() ? container.bitrate().getAsLong() : null;
      return this;
    }

    public MediaFileContainerInfoBuilder snapshot(SourceFileSnapshot snapshot) {
      sourceSize = snapshot.size();
      sourceModifiedEpochSecond = snapshot.modifiedAt().getEpochSecond();
      sourceModifiedNanos = snapshot.modifiedAt().getNano();
      return this;
    }
  }
}
