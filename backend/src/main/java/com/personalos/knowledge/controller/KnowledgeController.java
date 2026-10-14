package com.personalos.knowledge.controller;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.knowledge.dto.KnowledgeAskRequest;
import com.personalos.knowledge.dto.KnowledgeAskResponse;
import com.personalos.knowledge.dto.KnowledgeDocumentDeletedDto;
import com.personalos.knowledge.dto.KnowledgeDocumentDto;
import com.personalos.knowledge.dto.KnowledgeSearchRequest;
import com.personalos.knowledge.dto.KnowledgeSearchResponse;
import com.personalos.knowledge.service.KnowledgeDocumentService;
import com.personalos.knowledge.service.KnowledgeQaService;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库接口。
 *
 * <p>契约见 {@code specs/004-knowledge-rag/contracts/knowledge-api.md}。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeDocumentService documentService;
    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeQaService qaService;

    public KnowledgeController(KnowledgeDocumentService documentService,
                               KnowledgeRetrievalService retrievalService,
                               KnowledgeQaService qaService) {
        this.documentService = documentService;
        this.retrievalService = retrievalService;
        this.qaService = qaService;
    }

    /**
     * 上传文档并建立索引。
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeDocumentDto> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT, "请选择要上传的文件");
        }
        try {
            return ApiResponse.ok(documentService.ingest(file.getOriginalFilename(), title, file.getBytes()));
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT, "读取上传文件失败，请重试");
        }
    }

    /**
     * 文档列表，按上传时间倒序。
     */
    @GetMapping("/documents")
    public ApiResponse<List<KnowledgeDocumentDto>> listDocuments() {
        return ApiResponse.ok(documentService.list());
    }

    /**
     * 删除文档并清理其全部向量。
     */
    @DeleteMapping("/documents/{id}")
    public ApiResponse<KnowledgeDocumentDeletedDto> deleteDocument(@PathVariable UUID id) {
        int deletedChunks = documentService.delete(id);
        return ApiResponse.ok(new KnowledgeDocumentDeletedDto(true, deletedChunks));
    }

    /**
     * 检索（不触发模型生成）。
     */
    @PostMapping("/search")
    public ApiResponse<KnowledgeSearchResponse> search(@Valid @RequestBody KnowledgeSearchRequest request) {
        return ApiResponse.ok(new KnowledgeSearchResponse(request.query(),
                retrievalService.search(request.query(), request.topK())));
    }

    /**
     * 基于知识库问答，返回回答与引用。
     */
    @PostMapping("/ask")
    public ApiResponse<KnowledgeAskResponse> ask(@Valid @RequestBody KnowledgeAskRequest request) {
        return ApiResponse.ok(qaService.ask(request.question(), request.topK()));
    }
}
