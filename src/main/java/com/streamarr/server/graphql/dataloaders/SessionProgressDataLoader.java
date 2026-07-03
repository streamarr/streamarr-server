package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.graphql.CurrentUser;
import com.streamarr.server.graphql.dto.WatchProgressDto;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "watchProgress")
@RequiredArgsConstructor
public class SessionProgressDataLoader implements MappedBatchLoader<UUID, WatchProgressDto> {

  private final WatchStatusService watchStatusService;

  @Override
  public CompletionStage<Map<UUID, WatchProgressDto>> load(Set<UUID> mediaFileIds) {
    return CompletableFuture.supplyAsync(
        () ->
            watchStatusService
                .getProgressForMediaFiles(CurrentUser.id(), mediaFileIds)
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> toDto(e.getValue()))));
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
