package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Company;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID> {

  Set<Company> findCompaniesBySourceIdIn(List<String> sourceIds);

  @Query("SELECT c FROM Movie m JOIN m.studios c WHERE m.id = :movieId")
  List<Company> findByMovieId(@Param("movieId") UUID movieId);
}
