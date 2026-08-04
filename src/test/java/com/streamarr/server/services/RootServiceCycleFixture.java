package com.streamarr.server.services;

import com.streamarr.server.services.architecturefixture.SubdomainServiceCycleFixture;

public record RootServiceCycleFixture(SubdomainServiceCycleFixture dependency) {}
