package com.stockquant.server.researchselection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchSelectionPageControllerTest {

    @Test
    void directSelectionUrlRedirectsToTheExistingHashRoute() {
        assertEquals("redirect:/#/research-selection",
                new ResearchSelectionPageController().selectionPage());
    }
}
