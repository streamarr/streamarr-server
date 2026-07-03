package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.graphql.dto.WatchProgressDto;
import com.streamarr.server.services.user.CurrentUserService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "watchProgress")
@RequiredArgsConstructor
public class SessionProgressDataLoader implements MappedBatchLoader<UUID, WatchProgressDto> {

  private final WatchStatusService watchStatusService;
  private final CurrentUserService currentUserService;

  @Override
  public CompletionStage<Map<UUID, WatchProgressDto>> load(Set<UUID> mediaFileIds) {
    return CompletableFuture.supplyAsync(
        () -> {
          var userId = currentUserService.currentUserId();
          var progressMap = watchStatusService.getProgressForMediaFiles(userId, mediaFileIds);

          var result = new HashMap<UUID, WatchProgressDto>();
          for (var mediaFileId : mediaFileIds) {
            var wp = progressMap.get(mediaFileId);
            result.put(mediaFileId, wp != null ? toDto(wp) : null);
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
