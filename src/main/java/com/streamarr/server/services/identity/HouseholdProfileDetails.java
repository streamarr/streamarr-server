package com.streamarr.server.services.identity;

import com.streamarr.server.services.identity.AdministrationQueryService.ProfileAdministrationDetails;
import lombok.NonNull;

/**
 * One row of a Household's Profiles: the Profile's administrative details, whether it belongs to
 * another Household, whether the Household's PIN safety rule locks it there, and whether it is
 * available there.
 */
public record HouseholdProfileDetails(
    @NonNull ProfileAdministrationDetails administration,
    boolean visitor,
    boolean locked,
    boolean available) {}
