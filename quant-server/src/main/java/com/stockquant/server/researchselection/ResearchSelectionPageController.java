package com.stockquant.server.researchselection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Keeps the production selection page usable when opened without a hash. */
@Controller
@ConditionalOnProperty(prefix = "stockquant.production",
        name = "enabled", havingValue = "true")
public final class ResearchSelectionPageController {

    @GetMapping({"/research-selection", "/research-selection/"})
    public String selectionPage() {
        return "redirect:/#/research-selection";
    }
}
