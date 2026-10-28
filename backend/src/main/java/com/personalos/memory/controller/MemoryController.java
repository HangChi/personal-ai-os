package com.personalos.memory.controller;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.memory.dto.CreateMemoryRequest;
import com.personalos.memory.dto.MemoryDto;
import com.personalos.memory.dto.PinMemoryRequest;
import com.personalos.memory.dto.UpdateMemoryRequest;
import com.personalos.memory.service.MemoryService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 长期记忆管理接口。
 *
 * <p>契约见 {@code specs/008-long-term-memory/contracts/memory-api.md}。
 * 本层只做「解析请求 → 调用服务 → 包装响应」，不含业务逻辑（宪法原则 III）。
 *
 * <p>注意：这些端点只服务于**人**。模型侧只能通过 {@code saveMemory} 工具写入，
 * 删除与修改刻意不暴露给模型（宪法原则 II 的延伸：能影响后续所有对话的东西，不能同时拥有删除权）。
 */
@RestController
@RequestMapping("/api/v1/memories")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 全部记忆，按最近修改倒序。无记忆时返回空数组。 */
    @GetMapping
    public ApiResponse<List<MemoryDto>> list() {
        return ApiResponse.ok(memoryService.list());
    }

    /**
     * 新增记忆。内容与既有条目相同时不新增，返回既有条目（幂等）。
     */
    @PostMapping
    public ApiResponse<MemoryDto> create(@RequestBody(required = false) CreateMemoryRequest request) {
        return ApiResponse.ok(memoryService.create(request == null ? null : request.content()));
    }

    /** 编辑记忆内容。 */
    @PatchMapping("/{id}")
    public ApiResponse<MemoryDto> update(@PathVariable String id,
                                         @RequestBody(required = false) UpdateMemoryRequest request) {
        return ApiResponse.ok(memoryService.update(id, request == null ? null : request.content()));
    }

    /**
     * 置顶 / 取消置顶（V26）。
     *
     * <p>常驻条目无条件注入、优先占预算 —— 这是「记忆必须始终生效」的表达方式，
     * 替代了「按相似度召回」（见 specs/008 research R-03）。
     */
    @PatchMapping("/{id}/pin")
    public ApiResponse<MemoryDto> pin(@PathVariable String id,
                                      @RequestBody(required = false) PinMemoryRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "pinned 不能为空");
        }
        return ApiResponse.ok(memoryService.setPinned(id, request.pinned()));
    }

    /** 删除记忆。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        memoryService.delete(id);
        return ApiResponse.ok(null);
    }
}
