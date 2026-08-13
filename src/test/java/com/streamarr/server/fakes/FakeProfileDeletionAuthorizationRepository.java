package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileDeletionAuthorization;
import com.streamarr.server.repositories.auth.ProfileDeletionAuthorizationRepository;

public class FakeProfileDeletionAuthorizationRepository
    extends FakeJpaRepository<ProfileDeletionAuthorization>
    implements ProfileDeletionAuthorizationRepository {}
