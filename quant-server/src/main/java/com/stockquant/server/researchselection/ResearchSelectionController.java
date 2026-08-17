package com.stockquant.server.researchselection;

import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnProperty(prefix = "stockquant.production",
        name = "enabled", havingValue = "true")
@RequestMapping("/api/research-selection")
public final class ResearchSelectionController {
    private final ResearchSelectionService service;

    public ResearchSelectionController(ResearchSelectionService service) {
        this.service = service;
    }

    @GetMapping("/universe")
    public Map<String, Object> universe() {
        return Map.of("version", ResearchUniverseV1.VERSION,
                "size", ResearchUniverseV1.constituents().size(),
                "securities", ResearchUniverseV1.constituents());
    }

    @PostMapping("/runs")
    public ResponseEntity<?> start(@RequestBody(required = false)
                                   StartRequest request) {
        SelectionRequest selection = request == null
                ? SelectionRequest.immediate() : request.toSelection();
        return ResponseEntity.accepted().body(service.start(selection));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<?> run(@PathVariable long id) {
        var result = service.result(id);
        return result.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> service.summary(id)
                        .<ResponseEntity<?>>map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @GetMapping("/runs")
    public Object history(@RequestParam(defaultValue = "20") int limit) {
        return service.history(limit);
    }

    @GetMapping("/latest")
    public ResponseEntity<?> latest() {
        return service.latest().<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record StartRequest(Integer primaryWindow) {
        SelectionRequest toSelection() {
            int primary = primaryWindow == null ? 20 : primaryWindow;
            // V1.0.8 treats 120/250 as read-only historical targets.  The
            // current selection window remains 60 sessions, so insufficient
            // older history can never authorize a Provider backfill.
            int auxiliary = ResearchSelectionModels
                    .DEFAULT_AUXILIARY_WINDOW;
            return new SelectionRequest(
                    ResearchSelectionModels.TriggerMode.ON_DEMAND,
                    primary, auxiliary, 10, 5, true);
        }
    }
}
