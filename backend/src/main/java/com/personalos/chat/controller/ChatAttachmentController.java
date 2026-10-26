package com.personalos.chat.controller;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.common.api.ApiResponse;
import com.personalos.chat.service.ChatAttachmentService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 聊天附件的上传与下载（阶段 22）。
 *
 * <p>与消息本身解耦：附件先上传拿到标识，再随下一条消息的请求体带上。
 * 这样「选文件」失败不会让消息发出去，而消息发送也不必等待大文件传输完成。
 */
@RestController
@RequestMapping("/api/v1/chat/uploads")
public class ChatAttachmentController {

    private final ChatAttachmentService attachmentService;

    public ChatAttachmentController(ChatAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 上传一个附件，返回名片（标识 + 文件名 + 类型 + 大小）。
     *
     * <p>走统一信封而不是裸 DTO：项目约定所有 HTTP 接口同一结构（架构文档第 6 节），
     * 前端也只需一套解包逻辑。
     */
    @PostMapping
    public ApiResponse<ChatAttachmentDto> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(attachmentService.store(file));
    }

    /**
     * 取附件内容。
     *
     * <p>文件名走 RFC 5987 编码：中文文件名若直接塞进 Content-Disposition，
     * 部分浏览器会把它显示成乱码（这是「能用但难看」的经典一坑）。
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable String id, @RequestParam(required = false) String filename) {
        Resource resource = attachmentService.load(id);
        String displayName = filename == null || filename.isBlank() ? id : filename;
        String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                // 附件内容不可变（标识即内容寻址），可以长缓存
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePublic())
                .body(resource);
    }
}
