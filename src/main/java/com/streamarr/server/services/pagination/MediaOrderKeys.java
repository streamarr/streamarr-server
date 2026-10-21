package com.streamarr.server.services.pagination;

import com.streamarr.server.repositories.TitleSortRepository;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MediaOrderKeys {
  private final TitleSortRepository titles;

  @Transactional(readOnly = true)
  public Map<MediaOrder, String> getKeys(Set<MediaOrder> orders) {
    var normalized =
        titles.normalize(
            orders.stream()
                .filter(order -> order.by() == OrderMediaBy.TITLE && order.value() != null)
                .map(order -> order.value().toString())
                .collect(Collectors.toSet()));
    return orders.stream()
        .collect(
            Collectors.toMap(
                order -> order, order -> MediaOrderKey.encode(normalizedOrder(order, normalized))));
  }

  private MediaOrder normalizedOrder(MediaOrder order, Map<String, String> normalized) {
    if (order.by() != OrderMediaBy.TITLE || order.value() == null) {
      return order;
    }

    return MediaOrder.builder()
        .by(order.by())
        .direction(order.direction())
        .itemId(order.itemId())
        .value(normalized.get(order.value().toString()))
        .build();
  }
}
