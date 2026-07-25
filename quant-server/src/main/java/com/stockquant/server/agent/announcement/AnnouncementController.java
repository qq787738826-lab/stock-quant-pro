package com.stockquant.server.agent.announcement;

import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureResult;
import com.stockquant.server.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/research/announcements")
public class AnnouncementController {

    private final AnnouncementIngestionService ingestionService;

    public AnnouncementController(AnnouncementIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/captures")
    public ApiResponse<CaptureResult> capture(
            @Valid @RequestBody ManualCaptureRequest request
    ) {
        return ApiResponse.ok(ingestionService.capture(new CaptureRequest(
                request.symbol(), request.startDate(), request.endDate())));
    }

    public record ManualCaptureRequest(
            @Pattern(regexp = "^[0-9]{6}$") String symbol,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate
    ) {
    }
}
