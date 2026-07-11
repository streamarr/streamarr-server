package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Image;
import java.util.List;

public interface ImageRepositoryCustom {

  void insertAllIfAbsent(List<Image> images);
}
