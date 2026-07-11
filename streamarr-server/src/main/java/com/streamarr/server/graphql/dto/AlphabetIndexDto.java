package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.AlphabetLetter;

public record AlphabetIndexDto(AlphabetLetter letter, int count) {}
