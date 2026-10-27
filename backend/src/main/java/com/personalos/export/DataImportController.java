package com.personalos.export;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据导入端点。
 *
 * <p>与导出一致，**请求体就是文件原文**（不套信封）：导入的是导出文件本身，
 * 多包一层只会让「把文件直接 PUT 上来」多一步。
 * 响应仍是标准信封 —— 成功时要返回各模块的条数，那是有结构的数据。
 *
 * <p>路径刻意用 {@code /import} 而不是 {@code /export/import}：两者是对称的一对操作，
 * 谁也不是谁的子资源。
 */
@RestController
@RequestMapping("/api/v1")
public class DataImportController {

    private static final Logger log = LoggerFactory.getLogger(DataImportController.class);

    /**
     * 请求体上限 20MB。
     *
     * <p>个人库的完整导出通常几百 KB 到几 MB；20MB 足够覆盖十年量级，
     * 同时挡住「误传一个大文件把内存打满」——整份 JSON 会先读进内存再解析。
     */
    private static final int MAX_BODY_BYTES = 20 * 1024 * 1024;

    private final DataImportService importService;

    public DataImportController(DataImportService importService) {
        this.importService = importService;
    }

    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<DataImportService.ImportResult> importData(@RequestBody String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求体为空，请上传导出文件的内容");
        }
        if (rawJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件过大，上限 20MB");
        }
        log.info("收到数据导入请求，大小 {} 字符", rawJson.length());
        return ApiResponse.ok(importService.importAll(rawJson));
    }
}
