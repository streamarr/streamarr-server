package com.streamarr.server.services.identity;

import com.streamarr.server.services.identity.AdministrationQueryService.ProfileAdministrationDetails;
import lombok.NonNull;

/**
 * One row of the Profiles an Account manages: the Profile's administrative details and whether it
 * is available in the Household it belongs to.
 */
public record ManagedProfileDetails(
    @NonNull ProfileAdministrationDetails administration, boolean available) {}
