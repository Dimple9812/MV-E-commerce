package com.marketflow.platform.web;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Stable pagination envelope for list endpoints (never exposes Spring's Page JSON shape). */
public record PageView<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <E, T> PageView<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageView<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static <T> PageView<T> of(List<T> items, int page, int size, long total) {
        return new PageView<>(items, page, size, total, size == 0 ? 0 : (int) Math.ceil((double) total / size));
    }
}
