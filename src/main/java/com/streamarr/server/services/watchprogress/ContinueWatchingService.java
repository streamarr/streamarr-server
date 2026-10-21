package com.streamarr.server.services.watchprogress;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import com.streamarr.server.services.pagination.Row;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContinueWatchingService {

  private final ContinueWatchingList list;

  @Transactional(readOnly = true)
  public Map<UUID, Row<BaseCollectable<?>>> findMatchingItems(UUID profileId, Set<UUID> itemIds) {
    return list.rowsOf(profileId, itemIds);
  }

  // The page read, the read behind its position and the item loads must see one snapshot: a
  // deletion committed between them would shrink the page and report no next page while later
  // items remain.
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ListPage<BaseCollectable<?>> getPage(UUID profileId, PageRequest request) {
    return Pager.page(list, profileId, request);
  }
}
