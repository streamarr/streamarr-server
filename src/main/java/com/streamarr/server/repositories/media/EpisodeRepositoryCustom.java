package com.streamarr.server.repositories.media;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface EpisodeRepositoryCustom {

  Map<UUID, List<UUID>> findEpisodeIdsBySeasonIds(Collection<UUID> seasonIds);
}
