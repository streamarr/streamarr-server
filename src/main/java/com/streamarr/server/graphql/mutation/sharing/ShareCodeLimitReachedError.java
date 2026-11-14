package com.streamarr.server.graphql.mutation.sharing;

public record ShareCodeLimitReachedError(String message) implements IssueShareCodeError {}
