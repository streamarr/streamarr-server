package com.streamarr.server.graphql.dto;

/** The offerer's whole preflight: only these two facts about the target Household. */
public record SharePreflightView(boolean wouldLock, boolean nameConflict) {}
