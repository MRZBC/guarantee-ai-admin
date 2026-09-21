package com.guarantee.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 分页查询基类。所有列表查询 DTO 继承它，避免重复定义分页参数与边界保护。
 */
public class PageQuery {

    private static final int MAX_PAGE_SIZE = 200;

    @Min(value = 1, message = "页码不能小于 1")
    private int pageNum = 1;

    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = MAX_PAGE_SIZE, message = "每页条数不能超过 " + MAX_PAGE_SIZE)
    private int pageSize = 10;

    public int getPageNum() {
        return pageNum;
    }

    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    /** MyBatis 使用的偏移量。 */
    public int offset() {
        return (pageNum - 1) * pageSize;
    }
}
