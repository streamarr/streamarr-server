package com.streamarr.server.fakes;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.repositories.LibraryRepository;
import java.sql.SQLException;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public class FakeLibraryRepository extends FakeJpaRepository<Library> implements LibraryRepository {

  @Override
  public <S extends Library> S save(S entity) {
    // Mirrors library_filepath_uri_idx (V035).
    var duplicatePath =
        database.values().stream()
            .anyMatch(
                library ->
                    !library.getId().equals(entity.getId())
                        && library.getFilepathUri().equals(entity.getFilepathUri()));
    if (duplicatePath) {
      // Mirrors Spring's Hibernate translation: the constraint name rides the cause chain.
      var message = "duplicate key value violates unique constraint \"library_filepath_uri_idx\"";
      throw new DataIntegrityViolationException(
          message,
          new ConstraintViolationException(
              message, new SQLException(message, "23505"), "library_filepath_uri_idx"));
    }
    return super.save(entity);
  }

  @Override
  public List<Library> findAllByStatus(LibraryStatus status) {
    return database.values().stream().filter(lib -> lib.getStatus() == status).toList();
  }
}
