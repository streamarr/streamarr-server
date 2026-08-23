// Read authorization (ADR 0024 §Read authorization): reads are as specific as writes and there is
// no broad readProfile permission.

// Profile history, progress, and preferences: the legitimately selected Account, a
// ProfileManager, or a live ServerAdmin.
@id("view-profile-activity-as-selected-manager-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"viewProfileActivity",
  resource
)
when {
  (principal has selectedProfile && principal.selectedProfile == resource) ||
  (resource has managedByPrincipal && resource.managedByPrincipal) ||
  (principal has serverAdmin && principal.serverAdmin)
};

// HouseholdAdmins read their own Household's administration; ServerAdmin reads every Household.
@id("view-household-administration-as-household-admin-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"viewHouseholdAdministration",
  resource
)
when {
  (principal has householdRole && principal.householdRole == "ADMIN" &&
   principal has membershipHousehold && principal.membershipHousehold == resource) ||
  (principal has serverAdmin && principal.serverAdmin)
};

// Account administrative details: HouseholdAdmins for the Accounts of their Household; ServerAdmin.
@id("view-account-administration-as-household-admin-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"viewAccountAdministration",
  resource
)
when {
  (principal has householdRole && principal.householdRole == "ADMIN" &&
   principal has membershipHousehold && resource has membershipHousehold &&
   resource.membershipHousehold == principal.membershipHousehold) ||
  (principal has serverAdmin && principal.serverAdmin)
};

// Profile administrative details: its managers; HouseholdAdmins while it is available in their
// Household; ServerAdmin.
@id("view-profile-administration-as-manager-hosting-admin-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"viewProfileAdministration",
  resource
)
when {
  (resource has managedByPrincipal && resource.managedByPrincipal) ||
  (principal has householdRole && principal.householdRole == "ADMIN" &&
   resource has availableInPrincipalHousehold && resource.availableInPrincipalHousehold) ||
  (principal has serverAdmin && principal.serverAdmin)
};
