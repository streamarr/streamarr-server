package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.ArtworkStepsAttempt;
import com.streamarr.server.domain.media.Image;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ImageRepositoryCustom {

  /**
   * Inserts the images that do not exist yet and returns their ids.
   *
   * @throws com.streamarr.server.exceptions.ObsoleteArtworkStepsAttemptException if the attempt's
   *     generation is no longer the item's pending artwork request or the item no longer exists
   */
  Set<UUID> insertAllIfAbsent(List<Image> images, ArtworkStepsAttempt attempt);

  /**
   * Replaces one logical artwork and returns the paths of the replaced images.
   *
   * @throws com.streamarr.server.exceptions.ObsoleteArtworkStepsAttemptException if the attempt's
   *     generation is no longer the item's pending artwork request or the item no longer exists
   */
  List<String> replaceLogicalArtwork(List<Image> images, ArtworkStepsAttempt attempt);

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
