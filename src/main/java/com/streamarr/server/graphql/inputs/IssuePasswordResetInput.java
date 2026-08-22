package com.streamarr.server.graphql.inputs;

public record IssuePasswordResetInput(String accountId, String reason) {}
