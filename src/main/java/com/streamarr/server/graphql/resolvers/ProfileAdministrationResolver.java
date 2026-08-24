package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.dto.ProfileAdministration;
import com.streamarr.server.graphql.inputs.AdministrativelyResetProfilePinInput;
import com.streamarr.server.graphql.inputs.ChangeProfileKindInput;
import com.streamarr.server.graphql.inputs.CreateProfileInput;
import com.streamarr.server.graphql.inputs.DeleteProfileInput;
import com.streamarr.server.graphql.inputs.RemoveProfileMaximumAllowedRatingAgeInput;
import com.streamarr.server.graphql.inputs.RemoveProfilePinInput;
import com.streamarr.server.graphql.inputs.RenameProfileInput;
import com.streamarr.server.graphql.inputs.SetProfileMaximumAllowedRatingAgeInput;
import com.streamarr.server.graphql.inputs.SetProfilePictureInput;
import com.streamarr.server.graphql.inputs.SetProfilePinInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.profile.AdministrativelyResetProfilePinPayload;
import com.streamarr.server.graphql.mutation.profile.ChangeProfileKindPayload;
import com.streamarr.server.graphql.mutation.profile.CreateProfilePayload;
import com.streamarr.server.graphql.mutation.profile.DeleteProfilePayload;
import com.streamarr.server.graphql.mutation.profile.ProfileErrors;
import com.streamarr.server.graphql.mutation.profile.RemoveProfileMaximumAllowedRatingAgePayload;
import com.streamarr.server.graphql.mutation.profile.RemoveProfilePinPayload;
import com.streamarr.server.graphql.mutation.profile.RenameProfilePayload;
import com.streamarr.server.graphql.mutation.profile.SetProfileMaximumAllowedRatingAgePayload;
import com.streamarr.server.graphql.mutation.profile.SetProfilePicturePayload;
import com.streamarr.server.graphql.mutation.profile.SetProfilePinPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.AdministrationQueryService;
import com.streamarr.server.services.identity.ProfileAdministrationService;
import com.streamarr.server.services.identity.ProfileAdministrationService.CreateProfileCommand;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class ProfileAdministrationResolver {

  private final AuthorizationService authorizationService;
  private final ProfileAdministrationService profileAdministrationService;
  private final AdministrationQueryService administrationQueryService;

  @DgsQuery
  public ProfileAdministration profileAdministration(@InputArgument String profileId) {
    return administrationQueryService
        .profileAdministration(authorizationService.currentIdentity(), Ids.parseUuid(profileId))
        .map(view -> ProfileAdministration.from(view.profile(), view.linked()))
        .orElse(null);
  }

  @DgsMutation
  public CreateProfilePayload createProfile(@InputArgument CreateProfileInput input) {
    return MutationPayloads.payload(
        profileAdministrationService
            .createProfile(
                authorizationService.currentIdentity(),
                CreateProfileCommand.builder()
                    .householdId(Ids.parseUuid(input.householdId()))
                    .name(input.name())
                    .kind(input.kind())
                    .maximumAllowedRatingAge(input.maximumAllowedRatingAge())
                    .localManagerAccountId(
                        input.profileManagerAccountId() == null
                            ? null
                            : Ids.parseUuid(input.profileManagerAccountId()))
                    .build())
            .map(this::toDto),
        ProfileErrors::toCreateProfileError,
        CreateProfilePayload::new);
  }

  @DgsMutation
  public RenameProfilePayload renameProfile(@InputArgument RenameProfileInput input) {
    return MutationPayloads.payload(
        profileAdministrationService
            .renameProfile(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                input.name())
            .map(this::toDto),
        ProfileErrors::toRenameProfileError,
        RenameProfilePayload::new);
  }

  @DgsMutation
  public SetProfilePicturePayload setProfilePicture(@InputArgument SetProfilePictureInput input) {
    return MutationPayloads.payload(
        profileAdministrationService
            .setProfilePicture(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                input.picture())
            .map(this::toDto),
        ProfileErrors::toSetProfilePictureError,
        SetProfilePicturePayload::new);
  }

  @DgsMutation
  public ChangeProfileKindPayload changeProfileKind(@InputArgument ChangeProfileKindInput input) {
    return MutationPayloads.payload(
        profileAdministrationService
            .changeProfileKind(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                input.kind())
            .map(this::toDto),
        ProfileErrors::toChangeProfileKindError,
        ChangeProfileKindPayload::new);
  }

  @DgsMutation
  public SetProfileMaximumAllowedRatingAgePayload setProfileMaximumAllowedRatingAge(
      @InputArgument SetProfileMaximumAllowedRatingAgeInput input) {
    return MutationPayloads.payload(
        profileAdministrationService
            .setProfileContentCeiling(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                input.maximumAllowedRatingAge())
            .map(this::toDto),
        ProfileErrors::toSetProfileMaximumAllowedRatingAgeError,
        SetProfileMaximumAllowedRatingAgePayload::new);
  }

  @DgsMutation
  public RemoveProfileMaximumAllowedRatingAgePayload removeProfileMaximumAllowedRatingAge(
      @InputArgument RemoveProfileMaximumAllowedRatingAgeInput input) {
    return MutationPayloads.payload(
        profileAdministrationService
            .clearProfileContentCeiling(
                authorizationService.currentIdentity(), Ids.parseUuid(input.profileId()))
            .map(this::toDto),
        ProfileErrors::toRemoveProfileMaximumAllowedRatingAgeError,
        RemoveProfileMaximumAllowedRatingAgePayload::new);
  }

  @DgsMutation
  public SetProfilePinPayload setProfilePin(@InputArgument SetProfilePinInput input) {
    return MutationPayloads.payload(
        profileAdministrationService
            .setProfilePin(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                input.pin())
            .map(this::toDto),
        ProfileErrors::toSetProfilePinError,
        SetProfilePinPayload::new);
  }

  @DgsMutation
  public RemoveProfilePinPayload removeProfilePin(@InputArgument RemoveProfilePinInput input) {
    return MutationPayloads.payload(
        profileAdministrationService
            .removeProfilePin(
                authorizationService.currentIdentity(), Ids.parseUuid(input.profileId()))
            .map(this::toDto),
        ProfileErrors::toRemoveProfilePinError,
        RemoveProfilePinPayload::new);
  }

  @DgsMutation
  public AdministrativelyResetProfilePinPayload administrativelyResetProfilePin(
      @InputArgument AdministrativelyResetProfilePinInput input) {
    return MutationPayloads.payload(
        profileAdministrationService
            .administrativelyResetProfilePin(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                input.pin(),
                input.reason())
            .map(this::toDto),
        ProfileErrors::toAdministrativelyResetProfilePinError,
        AdministrativelyResetProfilePinPayload::new);
  }

  @DgsMutation
  public DeleteProfilePayload deleteProfile(@InputArgument DeleteProfileInput input) {
    return MutationPayloads.payload(
        profileAdministrationService.deleteProfile(
            authorizationService.currentIdentity(), Ids.parseUuid(input.profileId())),
        ProfileErrors::toDeleteProfileError,
        DeleteProfilePayload::new);
  }

  private ProfileAdministration toDto(Profile profile) {
    var view = administrationQueryService.profileAdministrationView(profile);
    return ProfileAdministration.from(view.profile(), view.linked());
  }
}
