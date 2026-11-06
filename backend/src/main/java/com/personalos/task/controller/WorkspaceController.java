package com.personalos.task.controller;

import com.personalos.common.api.*;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.idempotency.*;
import com.personalos.common.json.JsonCodec;
import com.personalos.task.dto.*;
import com.personalos.task.repository.WorkspaceRepository;
import com.personalos.task.service.WorkspaceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {
    private final WorkspaceService service;
    private final IdempotencyService idempotency;
    private final JsonCodec codec;
    public WorkspaceController(WorkspaceService service,IdempotencyService idempotency,JsonCodec codec){this.service=service;this.idempotency=idempotency;this.codec=codec;}
    @GetMapping("/projects") public ApiResponse<List<ProjectDto>> projects(){return ApiResponse.ok(service.projects());}
    @GetMapping("/workspace/runs") public ApiResponse<WorkspaceRepository.RunPage> runs(){return ApiResponse.ok(service.unfinishedRuns());}
    @PostMapping("/projects") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectDto> create(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProjectChange c){return save(null,key,c);}
    @PatchMapping("/projects/{id}")
    public ApiResponse<ProjectDto> update(@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProjectChange c){return save(id,key,c);}
    private ApiResponse<ProjectDto> save(UUID id,String key,ProjectChange c){
        if(key==null || key.isBlank()) return ApiResponse.ok(service.save(id,c));
        key=key.strip();
        if(!key.matches("[A-Za-z0-9._:-]{8,120}")) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"幂等键格式非法");
        var request=new IdempotencyRequest(key,OperationSource.HTTP,id==null?"POST /api/v1/projects":"PATCH /api/v1/projects/"+id,codec.digestOf(c),null,null);
        var result=idempotency.execute(request,()->IdempotentResult.of(codec.toJson(service.save(id,c)),IdempotencyOutcome.SUCCESS));
        if(result.responsePayload()==null) throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,"同一请求正在处理中，请稍后重试");
        return ApiResponse.ok(codec.fromJson(result.responsePayload(),ProjectDto.class));
    }
}
