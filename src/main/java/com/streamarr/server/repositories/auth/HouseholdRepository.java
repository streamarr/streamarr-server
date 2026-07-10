package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.Household;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HouseholdRepository extends JpaRepository<Household, UUID> {}
