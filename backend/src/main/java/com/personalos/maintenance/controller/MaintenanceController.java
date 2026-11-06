package com.personalos.maintenance.controller;

import com.personalos.common.api.ApiResponse;
import com.personalos.maintenance.service.MaintenanceService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/maintenance")
public class MaintenanceController {
    private final MaintenanceService service;
    public MaintenanceController(MaintenanceService service) { this.service = service; }
    @GetMapping public ApiResponse<Map<String, Object>> snapshot() { return ApiResponse.ok(service.snapshot()); }
    @PostMapping(value="/verify",consumes="application/json")
    public ApiResponse<Map<String, Object>> verify(HttpServletRequest request) throws IOException {
        return ApiResponse.ok(service.verify(request.getInputStream().readNBytes(MaintenanceService.MAX_BYTES + 1)));
    }
    @PostMapping(value="/drill-reports",consumes="application/json")
    public ApiResponse<Map<String, Object>> drill(HttpServletRequest request) throws IOException {
        return ApiResponse.ok(service.drill(request.getInputStream().readNBytes(16385)));
    }
}
