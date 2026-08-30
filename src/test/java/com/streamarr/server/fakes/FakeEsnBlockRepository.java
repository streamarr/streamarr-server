package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.repositories.auth.EsnBlockRepository;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public class FakeEsnBlockRepository extends FakeJpaRepository<EsnBlock>
    implements EsnBlockRepository {

  @Override
  public List<EsnBlock> findPageByHouseholdId(
      UUID householdId, KeysetPaginationOptions paginationOptions) {
    return FakeAuditableEntityPage.find(
        database.values(), block -> householdId.equals(block.getHouseholdId()), paginationOptions);
  }

  @Override
  public List<EsnBlock> findPageByHouseholdIdIsNull(KeysetPaginationOptions paginationOptions) {
    return FakeAuditableEntityPage.find(
        database.values(), block -> block.getHouseholdId() == null, paginationOptions);
  }

  @Override
  public <S extends EsnBlock> S save(S entity) {
    var duplicateScope =
        database.values().stream()
            .anyMatch(
                block ->
                    !Objects.equals(block.getId(), entity.getId())
                        && block.getEsn().equals(entity.getEsn())
                        && Objects.equals(block.getHouseholdId(), entity.getHouseholdId()));
    if (duplicateScope) {
      var constraint = "uq_esn_block_scope";
      var message = "duplicate key value violates unique constraint \"%s\"".formatted(constraint);
      throw new DataIntegrityViolationException(
          message,
          new ConstraintViolationException(
              message, new SQLException(message, "23505"), constraint));
    }

    return super.save(entity);
  }

  @Override
  public List<EsnBlock> findByHouseholdId(UUID householdId) {
    return database.values().stream()
        .filter(block -> householdId.equals(block.getHouseholdId()))
        .toList();
  }

  @Override
  public List<EsnBlock> findByHouseholdIdIsNull() {
    return database.values().stream().filter(block -> block.getHouseholdId() == null).toList();
  }

  @Override
  public Optional<EsnBlock> findByEsnAndHouseholdId(String esn, UUID householdId) {
    return database.values().stream()
        .filter(block -> esn.equals(block.getEsn()))
        .filter(block -> householdId.equals(block.getHouseholdId()))
        .findFirst();
  }

  @Override
  public Optional<EsnBlock> findByEsnAndHouseholdIdIsNull(String esn) {
    return database.values().stream()
        .filter(block -> esn.equals(block.getEsn()))
        .filter(block -> block.getHouseholdId() == null)
        .findFirst();
  }

  @Override
  public boolean existsByEsnAndHouseholdIdIsNull(String esn) {
    return findByEsnAndHouseholdIdIsNull(esn).isPresent();
  }

  @Override
  public boolean existsByEsnAndHouseholdId(String esn, UUID householdId) {
    return findByEsnAndHouseholdId(esn, householdId).isPresent();
  }
}
