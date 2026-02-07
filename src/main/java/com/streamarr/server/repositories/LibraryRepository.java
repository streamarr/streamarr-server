package com.streamarr.server.repositories;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LibraryRepository extends JpaRepository<Library, UUID> {

  boolean existsByFilepath(String filepath);

  List<Library> findAllByStatus(LibraryStatus status);
}
