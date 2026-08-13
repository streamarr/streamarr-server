# Streamarr Identity and Viewing

Streamarr separates the person or device that signs in from the identity whose viewing activity is recorded.
Profiles can travel between households without making households part of the viewing experience.

## Language

**Account**:
An authenticated login and security identity with exactly one home Household.
_Avoid_: User, viewing identity

**Household**:
A private administration, catalog, and local-policy boundary owned by one Account and shared by one or more Accounts.
It is not a viewing identity or a choice presented during normal viewing.
_Avoid_: Home selector, tenant

**Household Owner**:
The Account with final administrative authority over one Household.
A Household has exactly one Household Owner; a Parent may hold delegated local authority without becoming its owner.
_Avoid_: Profile owner, ServerAdmin

**Profile**:
A portable viewing identity that carries its history, preferences, Kid or Adult classification, content ceiling, and PIN wherever it is shared.
_Avoid_: Account, household profile

**Kid Profile**:
A Profile for someone who is not an adult.
Its content ceiling is separate from its Kid classification, so one Kid Profile may be less restrictive than another.
_Avoid_: Child account, restricted account

**Adult Profile**:
A Profile for an adult.
_Avoid_: Admin profile, unrestricted profile

**Profile Manager**:
An Account allowed to edit a Profile, offer it to a Household, invite additional managers, and participate in deletion decisions.
A Profile may have several equal Profile Managers and has no main or owning parent.
_Avoid_: Profile owner, main parent, ProfileAuthority

**Profile Share**:
A relationship that makes one Profile available to every Account in one Household without duplicating the Profile or its history.
Either endpoint may end it: the Household may remove the Profile, or a session actively using the Profile in that Household may make the Profile leave.
_Avoid_: Profile copy, Household membership

**Unshare**:
Ending one Profile Share because the Household removes the Profile or the Profile leaves the Household.
The Profile is no longer available in that Household, while the portable Profile and its data continue elsewhere.
_Avoid_: Revoke Profile, delete from Household

**Delete Profile**:
Permanently erasing a portable Profile and its data.
Ordinary deletion requires sharing and co-management to end first; ServerAdmin may perform an exceptional override.
_Avoid_: Revoke Profile, unshare
