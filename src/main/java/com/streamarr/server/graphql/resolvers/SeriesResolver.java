package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQuery;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class SeriesResolver {

  private final SeriesRepository seriesRepository;
  private final MediaFileRepository mediaFileRepository;

  @DgsQuery
  public Optional<Series> series(String id) {
    return seriesRepository.findById(parseUuid(id));
  }

  @DgsData(parentType = "Series", field = "files")
  public List<MediaFile> files(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return mediaFileRepository.findByMediaId(series.getId());
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }
}
