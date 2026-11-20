package com.grocery.ai.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkippedTools: answers the local model (qwen3:4b-instruct) really gave without the tool calls to back them, in the
 * natural-language tests of 2026-11-19, and answers that may stand.
 */
class SkippedToolsTest {

    private static final List<String> SOURCES = List.of("what's in my cart now?",
            "Context (from the server, trustworthy): user role CUSTOMER; cart is empty; latest order GA-T77BD07N: "
                    + "CANCELLED, ₹239.00, from Vijaya Nagar Fresh Mart, placed 19 Nov, 8:44 pm IST");

    private static final SkippedTools.Turn NO_TOOL = new SkippedTools.Turn(false, false, false);
    private static final SkippedTools.Turn SEARCHED = new SkippedTools.Turn(true, false, true);
    private static final SkippedTools.Turn ADDED = new SkippedTools.Turn(true, true, true);

    @Test
    void claimedChangesNoToolMadeAreSentBack() {
        for (String answer : List.of(
                "I've removed the tomatoes from your cart.",
                "I'll add 1 kg of tomatoes to your cart.",
                "I'll prepare your order for approval.",
                "I have now placed the order.",
                "Your rice has been added to the cart.",
                "Done! I just added 2 packets of basmati rice.",
                "Removed 1 kg Tomato from your cart.")) {
            assertThat(SkippedTools.check(answer, SOURCES, NO_TOOL)).as(answer).isEqualTo(SkippedTools.Reason.ACTION);
        }
        // live: a search ran, then "Added …" although addToCart never did
        String added = "Added 1 kg of Tomato from Vijaya Nagar Fresh Mart to your cart.";
        assertThat(SkippedTools.check(added, SOURCES, SEARCHED)).isEqualTo(SkippedTools.Reason.ACTION);
        assertThat(SkippedTools.check(added, SOURCES, ADDED)).isNull();
    }

    @Test
    void sayingTheDataCannotBeSeenWithoutLookingIsSentBack() {
        for (String answer : List.of(
                "I don't have access to the details of that order.",
                "I don't have the details for that order.",
                "I can't find that order.",
                "I'm unable to see your payments.")) {
            assertThat(SkippedTools.check(answer, SOURCES, NO_TOOL)).as(answer).isEqualTo(SkippedTools.Reason.UNSEEN);
            // after a tool call (one that failed, say) it may well be true
            assertThat(SkippedTools.check(answer, SOURCES, new SkippedTools.Turn(true, false, false))).isNull();
        }
    }

    @Test
    void figuresFromNowhereAreSentBackUnlessAToolReturnedData() {
        String cart = "Your cart has 2 kg of Daawat Basmati Rice (₹120.00 per kg), total ₹240.00.";
        assertThat(SkippedTools.check(cart, SOURCES, NO_TOOL)).isEqualTo(SkippedTools.Reason.FIGURES);
        assertThat(SkippedTools.check(cart, SOURCES, SEARCHED)).isNull();
    }

    @Test
    void answersFromTheContextOrWithoutClaimsStand() {
        for (String answer : List.of(
                "Your cart is currently empty.",
                "Your latest order, GA-T77BD07N, was cancelled. It cost ₹239.00.",
                "Would you like me to add it to your cart?",
                // offers, not claims (live: a retry for this only repeated it)
                "Would you like to proceed with adding Daawat Basmati Rice? If yes, I'll add it to your cart.",
                "Once you confirm, I'll prepare the order for you.",
                "Shall I add 1 kg of tomatoes?",
                "I can't offer discounts on your cart.",
                "Your order has been delivered.",
                "I'm the scripted assistant (no language model). I understand: “add <n> <product>”, “show my cart”.",
                "")) {
            assertThat(SkippedTools.check(answer, SOURCES, NO_TOOL)).as(answer).isNull();
        }
    }
}
