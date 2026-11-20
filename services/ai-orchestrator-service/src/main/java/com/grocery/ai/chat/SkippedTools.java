package com.grocery.ai.chat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Spots an answer that the turn's tool calls do not back. The small local model does this often (qwen3:4b-instruct,
 * natural-language tests of 2026-11-19): "I've removed the tomatoes" or "Added 1 kg of Tomato" with the cart untouched,
 * "I'll prepare your order" with nothing prepared, "I don't have access to the details of order GA-…" although
 * getOrderStatus reads it, or a delivery fee from nowhere. {@link ChatService} then asks the model once more and marks
 * an answer that is still not backed as not checked against the app's data.
 *
 * <ul>
 *   <li>{@link Reason#ACTION}: it claims a change (first person, or a sentence starting "Added …") and no tool that
 *       changes something ran in the turn (reads do not count: "Added" after a mere search is still a claim). A
 *       question or an offer ("If yes, I'll add it") is not a claim.</li>
 *   <li>{@link Reason#UNSEEN}: it says it cannot see or find something, and no tool was called at all.</li>
 *   <li>{@link Reason#FIGURES}: no tool returned data and it states figures found nowhere in what it was given
 *       ({@link Grounding}).</li>
 * </ul>
 */
final class SkippedTools {

    /** Why an answer is not backed (the {@code retry} event's code and the metric's tag). */
    enum Reason {
        ACTION,
        UNSEEN,
        FIGURES
    }

    /** What the turn's tool calls did. */
    record Turn(boolean calledTool, boolean changed, boolean gotData) {
    }

    private static final String VERBS = "add|added|adding|remove|removed|update|updated|place|placed|prepare|prepared"
            + "|cancel|cancelled|canceled|set|change|changed|empty|emptied|put|submit|submitted|request|requested"
            + "|raise|raised|file|filed|return|returned";

    /** "I've removed the tomatoes", "I'll add 1 kg", "I have now placed", "has been added", "successfully placed". */
    static final Pattern ACTION = Pattern.compile(
            "\\bI(?:['’]ve| have| just| will|['’]ll|['’]m going to| am going to)\\s+(?:now\\s+|also\\s+|just\\s+"
                    + "|go ahead and\\s+)?(?:" + VERBS + ")\\b"
                    + "|\\b(?:has|have) been (?:added|removed|updated|placed|prepared)\\b"
                    + "|\\bsuccessfully (?:added|removed|updated|placed|prepared|cancell?ed)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A sentence that starts with the change it reports: "Added 1 kg of Tomato …", "Done! Removed …". */
    private static final Pattern REPORTED_ACTION = Pattern.compile(
            "^(?:Done[!.,]?\\s*)?(?:Added|Removed|Updated|Placed|Prepared|Cancelled|Canceled|Emptied)\\b");

    /** "I don't have access to the details", "I can't find order …", "I'm unable to see", "I don't have the details". */
    static final Pattern UNSEEN = Pattern.compile(
            "\\bI\\s+(?:don['’]t|do not|can['’]t|cannot|couldn['’]t|could not|am unable to|am not able to)\\s+"
                    + "(?:have\\s+)?(?:access|see|find|view|look up|check|retrieve|fetch)\\b"
                    + "|\\bI['’]m (?:unable|not able) to (?:access|see|find|view|look up|check|retrieve|fetch)\\b"
                    + "|\\bI don['’]t have (?:any |the )?(?:details|information|data|records?)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Sentence ends (and line breaks): an action claim is judged per sentence. */
    private static final Pattern SENTENCES = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    /** "If yes, I'll add it", "Once you confirm, I'll prepare the order": an offer, not a claim. */
    private static final Pattern CONDITIONAL = Pattern.compile(
            "\\b(?:if|once|when|after|as soon as|shall|should|would you|do you want|want me)\\b",
            Pattern.CASE_INSENSITIVE);

    private SkippedTools() {
    }

    /**
     * Why {@code answer} is not backed by the turn's tool calls, or null when it may stand. {@code sources} is what
     * the model was given about this user (see {@link Grounding#ungroundedFigures}).
     */
    static Reason check(String answer, List<String> sources, Turn turn) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        if (!turn.changed() && claimsAction(answer)) {
            return Reason.ACTION;
        }
        if (!turn.calledTool() && UNSEEN.matcher(answer).find()) {
            return Reason.UNSEEN;
        }
        return turn.gotData() || Grounding.ungroundedFigures(answer, sources).isEmpty() ? null : Reason.FIGURES;
    }

    /** A sentence claiming a change, not offering one (a question or a condition is an offer). */
    private static boolean claimsAction(String answer) {
        for (String sentence : SENTENCES.split(answer)) {
            String text = sentence.strip().replaceFirst("^[-*•\\d.)\\s]+", "");      // list markers
            boolean claim = ACTION.matcher(text).find() || REPORTED_ACTION.matcher(text).find();
            if (claim && !text.endsWith("?") && !CONDITIONAL.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
