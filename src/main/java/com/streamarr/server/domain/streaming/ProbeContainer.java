package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record ProbeContainer(
    @NonNull Optional<String> format,
    @NonNull Optional<Duration> duration,
    @NonNull OptionalLong bitrate,
    @NonNull ChapterKnowledge chapters) {

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class ProbeContainerBuilder {
    private Optional<String> format = Optional.empty();
    private Optional<Duration> duration = Optional.empty();
    private OptionalLong bitrate = OptionalLong.empty();
    private ChapterKnowledge chapters = new ChapterKnowledge.NotReported();
  }
}
