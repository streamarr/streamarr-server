package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.graphql.dto.ChapterDto;
import com.streamarr.server.graphql.dto.ChapterStatus;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;

@DgsComponent
public class MediaFileFieldResolver {

  private static final String CHAPTER_LOADER = "chapters";

  @DgsData(parentType = "MediaFile", field = "chapters")
  public CompletableFuture<List<ChapterDto>> chapters(DataFetchingEnvironment dfe) {
    return loadChapters(dfe)
        .thenApply(
            chapters ->
                switch (chapters) {
                  case ChapterKnowledge.Known(var known) ->
                      known.stream().map(ChapterDto::from).toList();
                  case ChapterKnowledge.Omitted _, ChapterKnowledge.NotReported _ -> List.of();
                });
  }

  @DgsData(parentType = "MediaFile", field = "chapterStatus")
  public CompletableFuture<ChapterStatus> chapterStatus(DataFetchingEnvironment dfe) {
    return loadChapters(dfe)
        .thenApply(
            chapters ->
                switch (chapters) {
                  case ChapterKnowledge.Known _ -> ChapterStatus.KNOWN;
                  case ChapterKnowledge.Omitted _ -> ChapterStatus.OMITTED;
                  case ChapterKnowledge.NotReported _ -> ChapterStatus.UNKNOWN;
                });
  }

  private CompletableFuture<ChapterKnowledge> loadChapters(DataFetchingEnvironment dfe) {
    MediaFile mediaFile = dfe.getSource();
    DataLoader<UUID, ChapterKnowledge> loader = dfe.getDataLoader(CHAPTER_LOADER);
    return loader.load(mediaFile.getId());
  }
}
