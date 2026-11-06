package com.personalos.knowledge.capture;
import com.personalos.common.api.ApiResponse;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/knowledge/captures")
public class KnowledgeCaptureController {
    private final KnowledgeCaptureService service;
    public KnowledgeCaptureController(KnowledgeCaptureService service) {this.service=service;}
    public record Input(String url) {}
    @GetMapping public ApiResponse<Map<String,Object>> list() {return ApiResponse.ok(service.list());}
    @PostMapping public ApiResponse<KnowledgeCaptureService.Capture> create(@RequestBody Input input) {return ApiResponse.ok(service.capture(input.url()));}
    @PostMapping("/{id}/refresh") public ApiResponse<KnowledgeCaptureService.Capture> refresh(@PathVariable UUID id) {return ApiResponse.ok(service.refresh(id));}
    @GetMapping("/{id}/versions") public ApiResponse<Map<String,Object>> versions(@PathVariable UUID id) {return ApiResponse.ok(service.versions(id));}
}
