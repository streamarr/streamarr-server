package com.streamarr.server.services;

import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.repositories.RatingRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RatingService {

  private final RatingRepository ratingRepository;

  public Rating addRating(String source, String value) {
    return ratingRepository.save(Rating.builder().source(source).value(value).build());
  }

  public Optional<Rating> findById(UUID ratingId) {
    return ratingRepository.findById(ratingId);
  }
}
