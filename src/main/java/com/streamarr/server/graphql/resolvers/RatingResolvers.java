package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.graphql.dto.RatingInput;
import com.streamarr.server.repositories.RatingRepository;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.UUID;

@DgsComponent
@RequiredArgsConstructor
public class RatingResolvers {

    private final RatingRepository ratingRepository;

    @DgsMutation
    public Rating addRating(@InputArgument("input") RatingInput ratingInput) {

        return ratingRepository.save(Rating.builder()
            .createdBy(UUID.fromString(ratingInput.getUserId()))
            .source(ratingInput.getSource())
            .value(ratingInput.getValue())
            .build());
    }

    @DgsQuery
    public Optional<Rating> rating(String id) {

        return ratingRepository.findById(UUID.fromString(id));
    }
}
