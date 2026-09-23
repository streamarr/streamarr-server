package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Image;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ImageRepositoryCustom {

  Set<UUID> insertAllIfAbsent(List<Image> images);

  List<String> replaceLogicalArtwork(List<Image> images);

  /**
   * Locks the movies so no artwork or result can be added to them and returns the paths of their
   * artwork. Call it in the transaction that deletes the movies, before deleting them.
   */
  List<String> lockMoviesAndFindArtworkPaths(Collection<UUID> movieIds);

  /**
   * Locks the series with their seasons and episodes, like {@link #lockMoviesAndFindArtworkPaths},
   * and returns the paths of all their artwork.
   */
  List<String> lockSeriesAndFindArtworkPaths(Collection<UUID> seriesIds);
}
