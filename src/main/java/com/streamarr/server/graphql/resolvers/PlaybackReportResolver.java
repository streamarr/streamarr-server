package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.PlaybackReportDto;
import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.playbackreport.MarkPlaybackReportReadError;
import com.streamarr.server.graphql.mutation.playbackreport.MarkPlaybackReportReadInput;
import com.streamarr.server.graphql.mutation.playbackreport.MarkPlaybackReportReadPayload;
import com.streamarr.server.graphql.mutation.playbackreport.PlaybackReportErrors;
import com.streamarr.server.graphql.mutation.playbackreport.SharePlaybackReportError;
import com.streamarr.server.graphql.mutation.playbackreport.SharePlaybackReportInput;
import com.streamarr.server.graphql.mutation.playbackreport.SharePlaybackReportPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.services.pagination.ServerScope;
import com.streamarr.server.services.playbackreport.PlaybackReportService;
import com.streamarr.server.services.playbackreport.SharePlaybackReportCommand;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class PlaybackReportResolver {

  private static final int DEFAULT_PAGE_SIZE = 50;
  private static final ListIdentity PLAYBACK_REPORTS =
      new ListIdentity("playback-reports", ServerScope.SERVER);
  private static final String INVALID_ID = "Enter a valid ID.";

  private final AuthorizationService authorizationService;
  private final PlaybackReportService playbackReportService;
  private final PaginationService paginationService;
  private final PositionCursorCodec positionCursors;
  private final RelayConnectionAdapter relayConnectionAdapter;

  @DgsMutation
  public SharePlaybackReportPayload sharePlaybackReport(
      @InputArgument SharePlaybackReportInput input) {
    authorizationService.requireProfile();
    var command = SharePlaybackReportCommand.builder();
    return withShareId(
        input.playbackSessionId(),
        "playbackSessionId",
        playbackSessionId ->
            withShareId(
                input.streamSessionId(),
                "streamSessionId",
                streamSessionId ->
                    withShareId(
                        input.submissionId(),
                        "submissionId",
                        submissionId ->
                            share(
                                input,
                                command
                                    .playbackSessionId(playbackSessionId)
                                    .streamSessionId(streamSessionId)
                                    .submissionId(submissionId)))));
  }

  @DgsQuery
  public Connection<PlaybackReportDto> playbackReports(DataFetchingEnvironment dfe) {
    var request =
        positionCursors.pageRequest(
            PLAYBACK_REPORTS,
            ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE));
    var page =
        playbackReportService.playbackReports(authorizationService.currentIdentity(), request);
    return relayConnectionAdapter.toConnection(page, PLAYBACK_REPORTS, PlaybackReportDto::from);
  }

  @DgsQuery
  public PlaybackReportDto playbackReport(@InputArgument String id) {
    return playbackReportService
        .playbackReport(authorizationService.currentIdentity(), Ids.parseUuid(id))
        .map(PlaybackReportDto::from)
        .orElse(null);
  }

  @DgsQuery
  public int unreadPlaybackReportCount() {
    return playbackReportService.unreadPlaybackReportCount(authorizationService.currentIdentity());
  }

  @DgsMutation
  public MarkPlaybackReportReadPayload markPlaybackReportRead(
      @InputArgument MarkPlaybackReportReadInput input) {
    return MutationPayloads.withUuid(
        input.playbackReportId(),
        playbackReportId ->
            MutationPayloads.payload(
                playbackReportService.markPlaybackReportRead(
                    authorizationService.currentIdentity(), playbackReportId),
                PlaybackReportErrors::toError,
                (report, errors) ->
                    new MarkPlaybackReportReadPayload(report.map(PlaybackReportDto::from), errors)),
        () ->
            MutationPayloads.inputError(
                new MarkPlaybackReportReadError.InvalidIdError(
                    INVALID_ID, InputPath.of("playbackReportId")),
                MarkPlaybackReportReadPayload::new));
  }

  private SharePlaybackReportPayload share(
      SharePlaybackReportInput input,
      SharePlaybackReportCommand.SharePlaybackReportCommandBuilder command) {
    return MutationPayloads.payload(
        playbackReportService.sharePlaybackReport(
            command
                .identity(authorizationService.currentIdentity())
                .note(Optional.ofNullable(input.note()))
                .player(input.player().toFigures())
                .build()),
        PlaybackReportErrors::toError,
        PlaybackReportResolver::sharePayload);
  }

  private static SharePlaybackReportPayload withShareId(
      String id, String field, Function<UUID, SharePlaybackReportPayload> whenValid) {
    return MutationPayloads.withUuid(
        id,
        whenValid,
        () ->
            MutationPayloads.inputError(
                new SharePlaybackReportError.InvalidIdError(INVALID_ID, InputPath.of(field)),
                PlaybackReportResolver::sharePayload));
  }

  private static SharePlaybackReportPayload sharePayload(
      Optional<UUID> playbackReportId, List<SharePlaybackReportError> userErrors) {
    return new SharePlaybackReportPayload(playbackReportId.map(UUID::toString), userErrors);
  }
}
