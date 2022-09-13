package com.streamarr.server.repositories;

import com.streamarr.server.domain.BaseCollectable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface BaseCollectableRepository extends JpaRepository<BaseCollectable, UUID>, BaseCollectableRepositoryCustom {
}
