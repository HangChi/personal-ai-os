package com.personalos.common.confirmation;

import com.personalos.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工确认接口。
 *
 * <p><b>这是「模型无法自行完成删除」的关键</b>：确认只能经由此处的 HTTP 接口发生，
 * 而 Agent 的工具集合中不存在任何能调用它的工具（FR-013）。
 */
@RestController
@RequestMapping("/api/v1/agent/confirmations")
public class ConfirmationController {

    private final ConfirmationService confirmationService;

    public ConfirmationController(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    /**
     * 列出待确认操作。
     */
    @GetMapping
    public ApiResponse<List<ConfirmationDto>> list(
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) ConfirmationStatus status) {
        return ApiResponse.ok(confirmationService.list(conversationId, status));
    }

    /**
     * 确认并执行。
     */
    @PostMapping("/{id}/confirm")
    public ApiResponse<ConfirmationExecution> confirm(@PathVariable UUID id,
                                                      @Valid @RequestBody ConfirmationTokenRequest request) {
        return ApiResponse.ok(confirmationService.confirm(id, request.token()));
    }

    /**
     * 取消。
     */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable UUID id,
                                    @Valid @RequestBody ConfirmationTokenRequest request) {
        confirmationService.cancel(id, request.token());
        return ApiResponse.ok(null);
    }
}
