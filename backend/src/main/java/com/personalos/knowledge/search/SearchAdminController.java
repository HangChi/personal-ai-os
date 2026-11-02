package com.personalos.knowledge.search;
import com.personalos.common.api.ApiResponse;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/knowledge/search-index")
@ConditionalOnProperty(name="app.knowledge.search.enabled",havingValue="true")
public class SearchAdminController {
    private final SearchProjection projection;
    public SearchAdminController(SearchProjection projection){this.projection=projection;}
    @GetMapping public ApiResponse<Map<String,Long>> status(){return ApiResponse.ok(Map.of("pending",projection.pending()));}
    @PostMapping("/reindex") public ApiResponse<Map<String,Integer>> reindex(){return ApiResponse.ok(Map.of("queued",projection.reindex()));}
}
