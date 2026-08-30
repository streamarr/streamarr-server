package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.EsnBlock.ESN_BLOCK;

import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.jooq.generated.tables.records.EsnBlockRecord;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import jakarta.persistence.EntityManager;
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
  public List<EsnBlock> findPageByHouseholdId(
      UUID householdId, KeysetPaginationOptions paginationOptions) {
    return findPage(ESN_BLOCK.HOUSEHOLD_ID.eq(householdId), paginationOptions);
  }

  @Override
  public List<EsnBlock> findPageByHouseholdIdIsNull(KeysetPaginationOptions paginationOptions) {
    return findPage(ESN_BLOCK.HOUSEHOLD_ID.isNull(), paginationOptions);
  }

  private List<EsnBlock> findPage(Condition scope, KeysetPaginationOptions paginationOptions) {
    var request =
        AuditableEntityPageQuery.PageRequest.<EsnBlockRecord, EsnBlock>builder()
            .table(ESN_BLOCK)
            .createdOn(ESN_BLOCK.CREATED_ON)
            .id(ESN_BLOCK.ID)
            .scope(scope)
            .options(paginationOptions)
            .entityType(EsnBlock.class)
            .build();
    return new AuditableEntityPageQuery(dsl, entityManager).findPage(request);
  }
}
