package com.personalos.knowledge.jobs;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/knowledge/jobs")
public class KnowledgeJobController {
    private final KnowledgeIndexJobs jobs;
    public KnowledgeJobController(KnowledgeIndexJobs jobs) { this.jobs=jobs; }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KnowledgeJobRepository.Job> submit(@RequestPart("file") MultipartFile file,
            @RequestParam(required=false) String title, @RequestParam(required=false) UUID documentId,
            @RequestHeader(value="Idempotency-Key",required=false) UUID id) {
        try { return ApiResponse.ok(jobs.submit(id==null?UUID.randomUUID():id,documentId,file.getOriginalFilename(),title,file.getBytes())); }
        catch (IOException ex) { throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,"文件读取失败"); }
    }
    @GetMapping public ApiResponse<List<KnowledgeJobRepository.Job>> list() { return ApiResponse.ok(jobs.recent()); }
    @GetMapping("/{id}") public ApiResponse<KnowledgeJobRepository.Job> get(@PathVariable UUID id) { return ApiResponse.ok(jobs.get(id)); }
}
