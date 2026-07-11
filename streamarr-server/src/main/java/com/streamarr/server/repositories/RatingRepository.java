package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Rating;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RatingRepository extends JpaRepository<Rating, UUID> {

  List<Rating> findByMovie_Id(UUID movieId);
}
