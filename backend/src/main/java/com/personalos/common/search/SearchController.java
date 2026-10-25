package com.personalos.common.search;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨模块全局搜索端点（阶段 17）。
 *
 * <p>返回按模块分组的结果，条目带 {@code kind + id}；前端据此跳转到对应页面。
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    /** 每模块默认返回条数 */
    private static final int DEFAULT_LIMIT = 5;
    /** 每模块条数上限：全局搜索是导航，不是替代各模块自己的搜索 */
    private static final int MAX_LIMIT = 20;
    private static final int KEYWORD_MAX_LENGTH = 100;

    private final GlobalSearchService service;

    public SearchController(GlobalSearchService service) {
        this.service = service;
    }

    /**
     * 跨模块搜索。
     *
     * @param keyword 关键词，必填，trim 后 1–100 字符
     * @param limit   每模块返回条数，默认 5，最大 20
     */
    @GetMapping("/search")
    public ApiResponse<GlobalSearchResult> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "5") int limit) {
        String trimmed = keyword.strip();
        if (trimmed.isEmpty()) {
            // 刻意不接受空词：那等于一次全库导出，而不是搜索
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "搜索词不能为空");
        }
        if (trimmed.length() > KEYWORD_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "搜索词长度不能超过 " + KEYWORD_MAX_LENGTH + " 个字符");
        }
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return ApiResponse.ok(service.search(trimmed, effectiveLimit));
    }
}
