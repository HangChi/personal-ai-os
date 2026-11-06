package com.personalos.workflow.controller;
import com.personalos.workflow.service.WorkflowService;
import com.personalos.workflow.dto.WorkflowChange;
import com.personalos.common.api.ApiResponse;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {
 private final WorkflowService service;public WorkflowController(WorkflowService service){this.service=service;}
 @GetMapping public ApiResponse<Map<String,Object>> list(){return ApiResponse.ok(service.list());}
 @PostMapping public ApiResponse<WorkflowService.Definition> create(@RequestBody WorkflowChange c){return ApiResponse.ok(service.save(null,c));}
 @PatchMapping("/{id}") public ApiResponse<WorkflowService.Definition> update(@PathVariable UUID id,@RequestBody WorkflowChange c){return ApiResponse.ok(service.save(id,c));}
 @PostMapping("/{id}/run") public ApiResponse<WorkflowService.Execution> run(@PathVariable UUID id){return ApiResponse.ok(service.run(id));}
 @GetMapping("/{id}/runs") public ApiResponse<Map<String,Object>> history(@PathVariable UUID id){return ApiResponse.ok(service.history(id));}
 @PostMapping("/runs/{id}/retry") public ApiResponse<WorkflowService.Execution> retry(@PathVariable UUID id){return ApiResponse.ok(service.retry(id));}
}
