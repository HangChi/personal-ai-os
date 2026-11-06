package com.personalos.calendar.controller;

import com.personalos.calendar.service.CalendarExchangeService;
import com.personalos.common.api.ApiResponse;
import java.time.Instant;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/calendar/events")
public class CalendarExchangeController {
    private final CalendarExchangeService service;
    public CalendarExchangeController(CalendarExchangeService service) { this.service=service; }
    public record Input(String content,String digest) {}
    @GetMapping(value="/ics",produces="text/calendar;charset=UTF-8")
    public ResponseEntity<String> exportCalendar() {
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"personal-ai-os.ics\"").body(service.exportCalendar());
    }
    @PostMapping("/ics/preview") public ApiResponse<CalendarExchangeService.Preview> preview(@RequestBody Input input) { return ApiResponse.ok(service.preview(input.content())); }
    @PostMapping("/ics/import") public ApiResponse<CalendarExchangeService.Imported> importCalendar(@RequestBody Input input) { return ApiResponse.ok(service.importCalendar(input.content(),input.digest())); }
    @GetMapping("/conflicts") public ApiResponse<CalendarExchangeService.Conflicts> conflicts(@RequestParam Instant from,@RequestParam Instant to) { return ApiResponse.ok(service.conflicts(from,to)); }
}
