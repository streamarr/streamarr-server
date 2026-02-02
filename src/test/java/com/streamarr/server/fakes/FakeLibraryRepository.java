package com.streamarr.server.fakes;

import com.streamarr.server.domain.Library;
import com.streamarr.server.repositories.LibraryRepository;

public class FakeLibraryRepository extends FakeJpaRepository<Library>
    implements LibraryRepository {}
