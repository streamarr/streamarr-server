package com.streamarr.server.services.identity;

import com.streamarr.server.services.identity.AdministrationQueryService.ProfileAdministrationDetails;
import lombok.NonNull;

/**
 * One row of a Household's Profiles: the Profile's administrative details, whether it belongs to
 * another Household, and whether the Household's PIN safety rule locks it there.
 */
public record HouseholdProfileDetails(
    @NonNull ProfileAdministrationDetails administration, boolean visitor, boolean locked) {}
