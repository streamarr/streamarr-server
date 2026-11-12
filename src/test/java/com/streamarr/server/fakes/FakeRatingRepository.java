package com.streamarr.server.fakes;

import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.repositories.RatingRepository;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.NotImplementedException;

public class FakeRatingRepository extends FakeJpaRepository<Rating> implements RatingRepository {

  @Override
  public List<Rating> findByMovie_Id(UUID movieId) {
    throw new NotImplementedException();
  }
}
