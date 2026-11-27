package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.dto.ShareCodeDetails;
import java.util.List;
import java.util.Optional;

public record WithdrawShareCodePayload(
    Optional<ShareCodeDetails> shareCode, List<WithdrawShareCodeError> userErrors) {}
