package com.personalos.export;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据导出端点。
 *
 * <p><b>刻意不包 {@code ApiResponse} 信封</b>（spec FR-001）：这是一次文件下载，
 * 外层再套 {@code {code, message, data}} 会让任何下游解析都多一步，
 * 而它本身已经是「成功才拿到文件」的语义（失败走统一异常处理，返回的是信封）。
 *
 * @see DataExportService
 */
@RestController
@RequestMapping("/api/v1")
public class DataExportController {

    /** 文件名里的时间戳：UTC，避免同一台机器换时区后文件名顺序错乱 */
    private static final DateTimeFormatter FILENAME_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final DataExportService exportService;
    private final Clock clock;

    public DataExportController(DataExportService exportService, Clock clock) {
        this.exportService = exportService;
        this.clock = clock;
    }

    /**
     * 导出全部业务数据。
     *
     * @return JSON 文件（附件形式），含各模块快照与计数
     */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export() {
        String filename = "personal-ai-os-export-" + FILENAME_TIME.format(clock.instant()) + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(exportService.export());
    }
}
