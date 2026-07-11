package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Review;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

  List<Review> findByMovie_Id(UUID movieId);
}
