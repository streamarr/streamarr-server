package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.repositories.auth.ProfileRepository;

public class FakeProfileRepository extends FakeJpaRepository<Profile>
    implements ProfileRepository {}
