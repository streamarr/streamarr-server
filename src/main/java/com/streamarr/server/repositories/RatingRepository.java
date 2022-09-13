package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Rating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;


@Repository
public interface RatingRepository extends JpaRepository<Rating, UUID> {
}
