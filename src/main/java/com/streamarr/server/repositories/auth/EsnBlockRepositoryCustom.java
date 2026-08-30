package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import java.util.List;
import java.util.UUID;

public interface EsnBlockRepositoryCustom {

  List<EsnBlock> findPageByHouseholdId(UUID householdId, KeysetPaginationOptions paginationOptions);

  List<EsnBlock> findPageByHouseholdIdIsNull(KeysetPaginationOptions paginationOptions);
}
