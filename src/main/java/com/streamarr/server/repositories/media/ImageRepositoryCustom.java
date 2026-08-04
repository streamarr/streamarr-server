package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Image;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ImageRepositoryCustom {

  Set<UUID> insertAllIfAbsent(List<Image> images);
}
