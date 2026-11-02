package com.personalos.knowledge.governance;
import com.personalos.common.api.ApiResponse;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/knowledge/governance")
public class GovernanceController {
    private final KnowledgeGovernance governance;
    public GovernanceController(KnowledgeGovernance governance){this.governance=governance;}
    @GetMapping("/spaces") public ApiResponse<List<KnowledgeGovernance.Space>> spaces(){return ApiResponse.ok(governance.spaces());}
    public record NewSpace(String name) { }
    @PostMapping("/spaces") public ApiResponse<KnowledgeGovernance.Space> create(@RequestBody NewSpace request){return ApiResponse.ok(governance.createSpace(request.name()));}
    @GetMapping("/sources") public ApiResponse<List<KnowledgeGovernance.DocumentSource>> sources(){return ApiResponse.ok(governance.sources());}
    @PutMapping("/sources/{id}") public ApiResponse<SourceInfo> update(@PathVariable UUID id,@RequestBody KnowledgeGovernance.Metadata metadata){return ApiResponse.ok(governance.update(id,metadata));}
    @PostMapping("/sources/{id}/review") public ApiResponse<Map<String,Boolean>> review(@PathVariable UUID id){governance.retry(id);return ApiResponse.ok(Map.of("queued",true));}
    @GetMapping("/conflicts") public ApiResponse<List<KnowledgeGovernance.Conflict>> conflicts(){return ApiResponse.ok(governance.conflicts());}
    @PostMapping("/conflicts/{id}/resolve") public ApiResponse<Map<String,Boolean>> resolve(@PathVariable UUID id,@RequestBody KnowledgeGovernance.Resolution resolution){governance.resolve(id,resolution);return ApiResponse.ok(Map.of("resolved",true));}
}
