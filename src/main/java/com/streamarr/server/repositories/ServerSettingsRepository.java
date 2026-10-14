package com.streamarr.server.repositories;

import com.streamarr.server.domain.ServerSettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ServerSettingsRepository extends JpaRepository<ServerSettings, UUID> {}
