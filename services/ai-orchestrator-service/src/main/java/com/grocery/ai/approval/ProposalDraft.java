package com.grocery.ai.approval;

import java.math.BigDecimal;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

/**
 * What a sensitive tool call would do, built by the server from fresh data (never by the model): the exact HTTP call
 * and the summary rendered from it.
 *
 * @param body       the request body; keys sorted (canonical JSON), money with 2 decimals
 * @param onBehalfOf the customer it is for (customer actions), else null
 * @param amount     money involved (order total, refund amount), or null
 */
public record ProposalDraft(ApprovalAction action, UUID onBehalfOf, String method, String path,
                            SortedMap<String, Object> body, String title, List<ApprovalView.Line> lines,
                            BigDecimal amount, String currency) {
}
