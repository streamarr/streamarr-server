package com.streamarr.server.repositories.movie;

import com.streamarr.server.domain.media.MediaFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MediaFileRepository extends JpaRepository<MediaFile, UUID>, MediaFileRepositoryCustom {

    Optional<MediaFile> findFirstByFilepath(String filepath);
}
