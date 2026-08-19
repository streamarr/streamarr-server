# Streamarr Identity and Viewing

Streamarr separates the Account that signs in from the Profile whose viewing activity is recorded.
Authority is derived from relationships between Accounts, Households, Profiles, and Device registrations
([ADR 0024](https://github.com/streamarr/streamarr-adr/blob/main/adr/0024-identity-authority-by-relationship.adoc));
every authorization decision is made by Cedar behind `AuthorizationService`
([ADR 0025](https://github.com/streamarr/streamarr-adr/blob/main/adr/0025-cedar-decides-authorization.adoc)).
This file is the shared vocabulary; the ADRs hold the rules.

## Language

**Account**:
The login and security principal: email, password, administrative display name, enabled status, exactly one Household membership with a Household role, optional ServerAdmin authority, and exactly one Personal Profile.
An Account authenticates and sends requests; it is never a viewing identity.
_Avoid_: User, viewing identity, owner

**Household**:
A private administration, catalog, and local-policy boundary.
Every Account is a member of exactly one Household; every Profile belongs to exactly one Household.
_Avoid_: Home selector, tenant, home household

**Household role**:
`ADMIN` or `MEMBER`, held by an Account in its one membership Household.
A Household's first Account becomes HouseholdAdmin; after that, the Household always keeps at least one Account and one HouseholdAdmin until teardown.
_Avoid_: Owner, Parent, HouseholdOwner, account role

**HouseholdAdmin**:
An Account holding the `ADMIN` role in its Household.
Peer HouseholdAdmins share equal authority: edit Household settings, inspect and revoke its Devices, inspect its Accounts and available Profiles, create Profiles there, accept or reject share offers, remove shared Profiles, and supervise restricted Profiles while they are shared in.
They cannot manage Accounts, Household roles, or delete the Household — that is ServerAdmin work.
_Avoid_: Household Owner, Parent, main admin

**HouseholdMember**:
An Account holding the `MEMBER` role.
It may select available Profiles, enter required PINs, watch, manage its own credentials and details, and manage its own unrestricted Adult Personal Profile.
Membership alone grants no Profile-administration authority.
_Avoid_: Viewer, guest

**ServerAdmin**:
Server-wide authority held by an Account (`server_admin`), used only as a live database fact for the actions that need it.
ServerAdmin may inspect all application-domain data and use the explicit override or force operation for any recovery action, but never reads passwords, hashes, tokens, or one-time secrets.
The signed ServerAdmin claim in an access token is for routing and display only and is never authority.
_Avoid_: Superuser, root, admin role

**Profile**:
A portable viewing identity: name, picture, PIN, `KID` or `ADULT` kind, optional Content Ceiling, viewing history and progress, and preferences.
A Profile has no credentials, no session of its own, and cannot administer anything.
_Avoid_: Account, household profile, user

**Personal Profile**:
The one Profile that represents an Account (`personalProfileOf`).
Created with the Account, always available in the Account's own Household through a structural share, and transferred with the Account.
It is deleted with the Account unless ServerAdmin chooses the keep-Profile path, which removes the link only after the surviving Profile has a valid manager and home anchor.
_Avoid_: Owner profile, primary profile, account profile

**Profile Kind**:
`KID` or `ADULT`, stated explicitly and independent of the Content Ceiling.
_Avoid_: Classification, birthdate, profile age

**Content Ceiling**:
An optional maximum rating age carried by a Profile; no ceiling means unrestricted by this dimension.
It is never derived from a birthdate.
_Avoid_: Profile age, age calculation, parental rating

**Restricted Profile**:
A Profile with Kid kind or any Content Ceiling.
A restriction means supervision: an Adult never restricts themselves, and an Account whose Personal Profile is restricted holds no HouseholdAdmin, ServerAdmin, or direct ProfileManager authority.
_Avoid_: Child account, limited user

**Unrestricted Adult**:
Adult kind with no Content Ceiling.
An Account whose Personal Profile is an unrestricted Adult is *eligible* for administrative and manager authority and is *sovereign* over that Profile: it manages it without a stored manager row, may remove its direct managers, may end its non-structural shares, and may delete Account and Profile together after fresh reauthentication.
Self-deletion is rejected if any deletion invariant would be violated; the final Account is handled only by ServerAdmin Household teardown.
_Avoid_: Admin profile, full profile

**Profile PIN**:
A portable Profile-entry secret verified by the server; an effective PIN exists only when the stored hash is non-null and non-blank.
Verification happens only inside `POST /api/auth/select-profile`; clearing a PIN is refused while it would lock the Profile anywhere it is available.
_Avoid_: Account password, household PIN, client-verified PIN

**PIN safety / Locked Profile**:
If any Kid Profile is available in a Household, every Adult and every less-restricted Kid available there needs an effective PIN; one without it is *locked* in that Household — still visible in the picker, not selectable there.
The lock is evaluated at selection and refresh, is Household-local, and never blocks creating, transferring, or sharing a Kid.
_Avoid_: Safety version, household lock, blocked Kid

**ProfileManager**:
Durable, portable authority over a Profile: derived for a sovereign Account over its own Personal Profile, direct (`managerOf`) for everyone else.
A manager edits the Profile and its policy, manages its PIN, offers it to Households, invites other managers, inspects its activity, and participates in deletion.
A direct grant survives unsharing and lasts until relinquished, removed by the sovereign Account, overridden by ServerAdmin, or its Account is deleted.
_Avoid_: Profile owner, main parent, ProfileAuthority

**Home anchor**:
The required local management anchor of a Profile in the Household it belongs to: the linked Account for an unrestricted Adult Personal Profile; otherwise an eligible direct ProfileManager in that Household, who for a restricted Profile must also be a HouseholdAdmin there.
Transfer and every other authority or lifecycle change must leave each surviving Profile with a valid home anchor; an unlinked Profile transfer establishes an eligible destination manager before commit or is rejected.
_Avoid_: Owner, primary manager

**Supervise / Administer**:
Share-scoped, Household-derived authority of a HouseholdAdmin over a Profile shared into their Household: administer (see, remove) any shared Profile; supervise a *restricted* one (edit name, picture, ceiling, PIN — never its kind, never leaving it unrestricted).
It ends with the share and never creates a portable relationship.
_Avoid_: Manage (reserved for ProfileManager authority), own

**Profile Share**:
`sharedInto`: makes one Profile available to everyone who may use one Household without duplicating it.
Offered by a ProfileManager (or a sovereign Account for its own Profile), accepted by a target HouseholdAdmin; ended by a target HouseholdAdmin, a member who directly manages the Profile, the sovereign Account, or ServerAdmin.
_Avoid_: Profile copy, Household membership, grant

**Structural share**:
The share of an Account's Personal Profile into the Account's own Household.
Created with the Account, cannot be ended while the Account remains a member, and moves with an Account transfer.
_Avoid_: Default share, home share

**Unshare**:
Ending one Profile Share; the Profile is no longer available in that Household while the Profile and its data continue elsewhere.
Unsharing clears any selection of that Profile there and is not deletion.
_Avoid_: Revoke Profile, delete from Household

**Use a Household**:
The viewing access a member has in a Household — select any Profile available there and browse its catalog — also held by a visitor whose Personal Profile is actively shared there.
It grants no membership, Household role, or administrative visibility.
_Avoid_: Join, belong to, guest membership

**Context Household**:
The one Household a session is currently using: the membership Household by default, the Household chosen in Streamarr-web, or the Device's registered Household.
Carried as one signed claim; refresh re-validates it and falls back to the membership Household and the Profile picker.
_Avoid_: Active household, selected household, home household

**Selected Profile**:
The Profile a session is currently watching as, recorded on the session and signed into a Profile-scoped access token after `POST /api/auth/select-profile`.
_Avoid_: Active profile, current user

**Device registration**:
A durable link of a shared TV (identified by its ESN) to exactly one Household (`registeredTo`) and the Account that linked it (`authorizedBy`).
Device-bound sessions may select Profiles, enter PINs, watch, update viewing state and preferences, and sign out; authorization forbids them every administrative action and they cannot switch Household.
_Avoid_: Paired device, device account

**ESN block**:
A server-wide or Household-scoped refusal of a Device ESN; a block leaves no matching registration or refreshable device session.
_Avoid_: Device ban, blacklist

**Fresh reauthentication**:
A current `reauthenticated_at` claim on the access token, obtained through `POST /api/auth/reauth`, required for every operation a stolen token must not be enough for (`requiresFreshReauthentication`).
There is no password field on any GraphQL mutation.
_Avoid_: Password confirmation, PasswordProof, sudo mode

**Transfer**:
Moving an Account (always together with its Personal Profile) or an unlinked Profile to another Household while preserving data, managers, and other shares.
Account transfer chooses `sourceAccess` `END` (default) or `KEEP_AS_VISITOR`.
_Avoid_: Move household, reassign owner

**Delete Profile**:
Permanently erasing a Profile and its data.
Ordinary deletion is for an unlinked Profile by its final manager after fresh reauthentication; a linked pair is erased only with its Account; ServerAdmin may force-delete with fresh reauthentication and a reason.
_Avoid_: Revoke Profile, unshare, archive

**Household teardown**:
ServerAdmin deleting a Household atomically with the disposition of its final Account.
Profiles worth keeping are transferred out first; teardown deletes every Profile that still belongs to the Household and only unshares Profiles that merely visit it.
_Avoid_: Delete household (as an ordinary mutation), purge
