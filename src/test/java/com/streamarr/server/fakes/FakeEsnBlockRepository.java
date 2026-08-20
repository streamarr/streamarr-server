package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.repositories.auth.EsnBlockRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeEsnBlockRepository extends FakeJpaRepository<EsnBlock>
    implements EsnBlockRepository {

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
