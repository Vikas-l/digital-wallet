package com.digitalwallet.wallet.web;

import java.util.List;

import org.springframework.data.domain.Page;

/** Stable JSON shape for paged results (Spring's PageImpl is not a stable API contract). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
