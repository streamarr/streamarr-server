package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.watchprogress.WatchProgressDto;
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
    return CompletableFuture.completedFuture(loadProgress(keys));
  }

  private Map<SessionProgressLoaderKey, WatchProgressDto> loadProgress(
      Set<SessionProgressLoaderKey> keys) {
    var result = new HashMap<SessionProgressLoaderKey, WatchProgressDto>();
    var keysByUser =
        keys.stream().collect(Collectors.groupingBy(SessionProgressLoaderKey::profileId));

    for (var entry : keysByUser.entrySet()) {
      var mediaFileIds =
          entry.getValue().stream().map(SessionProgressLoaderKey::mediaFileId).toList();
      var progressMap = watchStatusService.getProgressForMediaFiles(entry.getKey(), mediaFileIds);

      for (var key : entry.getValue()) {
        var progress = progressMap.get(key.mediaFileId());
        if (progress != null) {
          result.put(key, WatchProgressDto.from(progress));
        }
      }
    }

    return result;
  }
}
