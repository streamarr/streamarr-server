package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.graphql.dto.RatingInput;
import com.streamarr.server.repositories.RatingRepository;
import com.streamarr.server.services.authorization.AuthorizationService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class RatingResolvers {

  private final RatingRepository ratingRepository;
  private final AuthorizationService authorizationService;

  @DgsMutation
  public Rating addRating(@InputArgument("input") RatingInput ratingInput) {
    authorizationService.requireProfile();
    return ratingRepository.save(
        Rating.builder()
            .createdBy(parseUuid(ratingInput.getUserId()))
            .source(ratingInput.getSource())
            .value(ratingInput.getValue())
            .build());
  }

  @DgsQuery
  public Optional<Rating> rating(String id) {
    authorizationService.requireProfile();
    return ratingRepository.findById(parseUuid(id));
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }
}
