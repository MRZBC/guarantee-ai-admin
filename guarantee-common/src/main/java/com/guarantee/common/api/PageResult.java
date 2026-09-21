package com.guarantee.common.api;

import java.util.List;

/**
 * 统一分页结构。
 *
 * @param pageNum  当前页码，从 1 开始
 * @param pageSize 每页条数
 * @param total    总记录数
 * @param list     当前页数据
 */
public record PageResult<T>(long pageNum, long pageSize, long total, List<T> list) {

    public static <T> PageResult<T> of(long pageNum, long pageSize, long total, List<T> list) {
        return new PageResult<>(pageNum, pageSize, total, list);
    }

    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(pageNum, pageSize, 0L, List.of());
    }

    public long totalPages() {
        return pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
    }
}
