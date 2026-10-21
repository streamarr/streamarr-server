package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.EsnBlock.ESN_BLOCK;

import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class EsnBlockRepositoryCustomImpl implements EsnBlockRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public List<Row<EsnBlock>> rowsInHousehold(UUID householdId, Seek seek) {
    return rowsBeyond(ESN_BLOCK.HOUSEHOLD_ID.eq(householdId), seek);
  }

  @Override
  public List<Row<EsnBlock>> rowsServerWide(Seek seek) {
    return rowsBeyond(ESN_BLOCK.HOUSEHOLD_ID.isNull(), seek);
  }

  private List<Row<EsnBlock>> rowsBeyond(Condition scope, Seek seek) {
    var keyset =
        Keyset.<OffsetDateTime>builder()
            .sortValue(ESN_BLOCK.CREATED_ON)
            .itemId(ESN_BLOCK.ID)
            .build();
    var query = keyset.rowsBeyond(keyset.selectFrom(dsl, ESN_BLOCK), scope, seek);
    return JooqQueryHelper.nativeRows(entityManager, query, EsnBlock.class, keyset);
  }
}
