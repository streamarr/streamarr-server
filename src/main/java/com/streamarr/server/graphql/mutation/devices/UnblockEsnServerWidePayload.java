package com.streamarr.server.graphql.mutation.devices;

import java.util.List;

public record UnblockEsnServerWidePayload(String esn, List<UnblockEsnServerWideError> userErrors) {}
