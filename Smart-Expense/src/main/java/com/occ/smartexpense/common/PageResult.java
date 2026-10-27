package com.occ.smartexpense.common;

import lombok.Getter;

import java.util.List;

/**
 * 统一分页结构，对应 docs/03-接口清单.md §1.1：
 * { "records": [], "total": 0, "page": 1, "size": 20 }
 */
@Getter
public class PageResult<T> {

    private final List<T> records;
    private final long total;
    private final int page;
    private final int size;

    private PageResult(List<T> records, long total, int page, int size) {
        this.records = records;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        return new PageResult<>(records, total, page, size);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), 0L, page, size);
    }
}
