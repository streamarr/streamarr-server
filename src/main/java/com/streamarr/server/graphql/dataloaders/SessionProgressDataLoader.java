package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.graphql.dto.WatchProgressDto;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "watchProgress")
@RequiredArgsConstructor
public class SessionProgressDataLoader
    implements MappedBatchLoader<SessionProgressLoaderKey, WatchProgressDto> {

  private final WatchStatusService watchStatusService;

  @Override
  public CompletionStage<Map<SessionProgressLoaderKey, WatchProgressDto>> load(
      Set<SessionProgressLoaderKey> keys) {
    return CompletableFuture.supplyAsync(
        () -> {
          var result = new HashMap<SessionProgressLoaderKey, WatchProgressDto>();
          var keysByUser =
              keys.stream().collect(Collectors.groupingBy(SessionProgressLoaderKey::userId));

          for (var entry : keysByUser.entrySet()) {
            var mediaFileIds =
                entry.getValue().stream().map(SessionProgressLoaderKey::mediaFileId).toList();
            var progressMap =
                watchStatusService.getProgressForMediaFiles(entry.getKey(), mediaFileIds);

            for (var key : entry.getValue()) {
              var progress = progressMap.get(key.mediaFileId());
              if (progress != null) {
                result.put(key, toDto(progress));
              }
            }
          }

          return result;
        });
  }

  private static WatchProgressDto toDto(SessionProgress wp) {
    return WatchProgressDto.builder()
        .positionSeconds(wp.getPositionSeconds())
        .percentComplete(wp.getPercentComplete())
        .durationSeconds(wp.getDurationSeconds())
        .lastModifiedOn(wp.getLastModifiedOn())
        .build();
  }
}
