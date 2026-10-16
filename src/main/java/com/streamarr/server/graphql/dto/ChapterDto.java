package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.streaming.Chapter;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import lombok.Builder;

@Builder
public record ChapterDto(Double startSeconds, Double endSeconds, String title) {

  public static ChapterDto from(Chapter chapter) {
    return ChapterDto.builder()
        .startSeconds(seconds(chapter.startTime()))
        .endSeconds(seconds(chapter.endTime()))
        .title(chapter.title().orElse(null))
        .build();
  }

  // BigDecimal rounds the exact time once, so -0.041 s reads as -0.041 rather than as the sum of
  // -1 s and 0.959 s.
  private static Double seconds(Optional<Duration> time) {
    return time.map(
            duration ->
                BigDecimal.valueOf(duration.getSeconds())
                    .add(BigDecimal.valueOf(duration.getNano(), 9))
                    .doubleValue())
        .orElse(null);
  }
}
