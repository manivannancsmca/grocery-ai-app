package com.grocery.ai.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Figures in an answer that nothing the model was given contains. */
class GroundingTest {

    private static final String CONTEXT = "Context (from the server, trustworthy): user role SUPER_ADMIN; today "
            + "2026-11-15 (India).";

    @Test
    void aNumberFromNowhereIsReported() {
        assertThat(Grounding.ungroundedFigures("112 vendors have been approved by the admin.",
                List.of("How many vendors have been approved by the admin?", CONTEXT))).containsExactly("112");
    }

    @Test
    void numbersTheUserTheHistoryOrTheContextGaveAreFine() {
        List<String> sources = List.of("Find paneer under ₹200", CONTEXT,
                "Earlier answer.\n[Tool results behind this answer: getSalesReport → {\"grossSales\":1890.00,"
                        + "\"ordersReceived\":12}]");
        assertThat(Grounding.ungroundedFigures("Paneer under ₹200: you had 12 orders worth ₹1,890 on 2026-11-15.",
                sources)).isEmpty();
    }

    @Test
    void anOrderNumberFromNowhereIsReportedOneFromTheContextIsNot() {
        // The live test's failure: a small model copied the example order number of a tool description.
        assertThat(Grounding.ungroundedFigures("Your latest order GA-7K3F9Q2M is DELIVERED.",
                List.of("Where is my latest order?", CONTEXT))).containsExactly("GA-7K3F9Q2M");
        assertThat(Grounding.ungroundedFigures("Your latest order GA-AB12CD34 is out for delivery.",
                List.of("Where is my latest order?", CONTEXT + " latest order GA-AB12CD34: OUT_FOR_DELIVERY")))
                .isEmpty();
        assertThat(Grounding.ungroundedFigures("Order ga-ab12cd34 again.", List.of("status of GA-AB12CD34")))
                .isEmpty();
        assertThat(Grounding.ungroundedFigures("Send an e-mail or a pre-order.", List.of())).isEmpty();
    }

    @Test
    void singleDigitsAndTrailingPunctuationAreIgnored() {
        assertThat(Grounding.ungroundedFigures("Step 1: open the menu. Then pick 2 options.", List.of())).isEmpty();
        assertThat(Grounding.ungroundedFigures("You have 45, then 4.50.", List.of("45 and 4.5")))
                .isEmpty();
        assertThat(Grounding.ungroundedFigures("", List.of())).isEmpty();
        assertThat(Grounding.ungroundedFigures("It costs 99 or 99.", List.of())).containsExactly("99");
    }
}
