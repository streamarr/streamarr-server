package com.streamarr.server.repositories.media;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MediaFileRepositoryCustom {

  List<UUID> findMediaFileIdsByMediaIds(Collection<UUID> mediaIds);
}
