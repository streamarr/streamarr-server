package com.streamarr.server.domain.media;

import jakarta.persistence.Embeddable;

@Embeddable
public record ContentRating(String system, String value, String country) {}
