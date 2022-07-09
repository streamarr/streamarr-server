package com.streamarr.server.repositories;

import com.streamarr.server.domain.BaseCollectable;

import java.util.List;

public interface BaseCollectableRepositoryCustom {

    List<BaseCollectable> getBaseCollectableEntities();
}
