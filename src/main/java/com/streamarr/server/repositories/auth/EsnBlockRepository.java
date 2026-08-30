package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.EsnBlock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EsnBlockRepository
    extends JpaRepository<EsnBlock, UUID>, EsnBlockRepositoryCustom {

  List<EsnBlock> findByHouseholdId(UUID householdId);

  List<EsnBlock> findByHouseholdIdIsNull();

  Optional<EsnBlock> findByEsnAndHouseholdId(String esn, UUID householdId);

  Optional<EsnBlock> findByEsnAndHouseholdIdIsNull(String esn);

  boolean existsByEsnAndHouseholdIdIsNull(String esn);

  boolean existsByEsnAndHouseholdId(String esn, UUID householdId);
}
