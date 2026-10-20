package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.util.List;
import java.util.UUID;

public interface EsnBlockRepositoryCustom {

  /** The Household's ESN blocks beyond the seek, by creation time, then ID. */
  List<Row<EsnBlock>> rowsInHousehold(UUID householdId, Seek seek);

  /** The server-wide ESN blocks beyond the seek, by creation time, then ID. */
  List<Row<EsnBlock>> rowsServerWide(Seek seek);
}
