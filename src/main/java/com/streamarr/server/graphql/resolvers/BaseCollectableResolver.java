package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.repositories.media.MediaFileRepository;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class BaseCollectableResolver {

  private final MediaFileRepository mediaFileRepository;

  @DgsData(parentType = "BaseCollectable", field = "files")
  public List<MediaFile> files(DataFetchingEnvironment dfe) {
    BaseCollectable<?> collectable = dfe.getSource();
    return mediaFileRepository.findByMediaId(collectable.getId());
  }
}
