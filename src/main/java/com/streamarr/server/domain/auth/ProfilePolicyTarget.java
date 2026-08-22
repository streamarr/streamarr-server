package com.streamarr.server.domain.auth;

/** The kind and ceiling a policy transition writes. */
public record ProfilePolicyTarget(ProfileKind kind, Integer maximumAllowedRatingAge) {}
