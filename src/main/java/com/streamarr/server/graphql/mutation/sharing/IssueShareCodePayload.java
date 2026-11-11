package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.dto.IssuedShareCodeDetails;
import java.util.List;
import java.util.Optional;

public record IssueShareCodePayload(
    Optional<IssuedShareCodeDetails> issued, List<IssueShareCodeError> userErrors) {}
