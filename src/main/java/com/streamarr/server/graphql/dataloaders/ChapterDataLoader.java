package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.services.probe.PersistedProbeReader;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/** Batches the chapters of the media files in one response, keyed by media file id. */
@DgsDataLoader(name = "chapters")
@RequiredArgsConstructor
public class ChapterDataLoader implements MappedBatchLoader<UUID, ChapterKnowledge> {

  private final PersistedProbeReader persistedProbeReader;

  @Override
  public CompletionStage<Map<UUID, ChapterKnowledge>> load(Set<UUID> mediaFileIds) {
    return CompletableFuture.completedFuture(persistedProbeReader.findChapters(mediaFileIds));
  }
}
