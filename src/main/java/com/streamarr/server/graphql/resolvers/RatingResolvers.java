package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.graphql.dto.RatingInput;
import com.streamarr.server.services.RatingService;
import com.streamarr.server.services.authorization.AuthorizationService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class RatingResolvers {

  private final RatingService ratingService;
  private final AuthorizationService authorizationService;

  @DgsMutation
  public Rating addRating(@InputArgument("input") RatingInput ratingInput) {
    authorizationService.requireProfile();
    return ratingService.addRating(ratingInput.getSource(), ratingInput.getValue());
  }

  @DgsQuery
  public Optional<Rating> rating(String id) {
    authorizationService.requireProfile();
    return ratingService.findById(parseUuid(id));
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }
}
