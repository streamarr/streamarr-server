package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.AlphabetLetter;
import java.util.List;

public record MediaFilterInput(
    AlphabetLetter startLetter,
    List<String> genreIds,
    List<Integer> years,
    List<String> contentRatings,
    List<String> studioIds,
    List<String> directorIds,
    List<String> castMemberIds,
    Boolean unmatched) {}
