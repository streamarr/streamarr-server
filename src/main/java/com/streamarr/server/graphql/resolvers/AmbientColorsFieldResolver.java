package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.AmbientTheme;
import com.streamarr.server.services.metadata.color.AmbientThemeDeriver;
import graphql.schema.DataFetchingEnvironment;

@DgsComponent
public class AmbientColorsFieldResolver {

  @DgsData(parentType = "AmbientColors", field = "theme")
  public AmbientTheme theme(DataFetchingEnvironment dfe) {
    AmbientColors colors = dfe.getSource();
    return AmbientThemeDeriver.derive(colors);
  }
}
