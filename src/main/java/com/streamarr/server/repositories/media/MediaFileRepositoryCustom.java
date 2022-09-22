package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.MediaFile;
import io.vertx.core.Future;

public interface MediaFileRepositoryCustom {

    Future<MediaFile> saveAsync(MediaFile mediaFile);

}
