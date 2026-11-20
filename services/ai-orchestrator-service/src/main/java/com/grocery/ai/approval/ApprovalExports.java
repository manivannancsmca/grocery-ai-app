package com.grocery.ai.approval;

import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;

/**
 * The CSV / Excel / PDF export of the approval list ({@code GET /api/approvals/export}): the same rows the caller's
 * list shows (an admin's queue, a customer's own requests).
 */
public final class ApprovalExports {

    private ApprovalExports() {
    }

    /**
     * Newest first like the list; a client may sort by request time, title or requester (the admin page's column keys
     * {@code requested}, {@code request}, {@code requester}; never-null columns). Not by status: the page sorts it by
     * its words ("Waiting for approval"), whose order differs from the codes'.
     */
    public static final ExportSort<ApprovalRequest> SORT = ExportSort.<ApprovalRequest>by(
                    Key.desc("createdAt", ApprovalRequest::getCreatedAt), Key.desc("id", ApprovalRequest::getId))
            .option("requested", "createdAt", ApprovalRequest::getCreatedAt)
            .option("request", "title", ApprovalRequest::getTitle)
            .option("requester", "requestedBy", ApprovalRequest::getRequestedBy)
            .build();

    /**
     * The list's columns, the same for every caller. Not the summary lines, the assistant's note or the decision
     * comment: free text that can hold a delivery address or a phone number (each request's page shows them).
     */
    public static final ExportTable<ApprovalView> APPROVALS = ExportTable.<ApprovalView>named(
                    "approval-requests", "Approval requests")
            .dateTime("Requested", ApprovalView::createdAt)
            .text("Request", view -> view.summary() == null ? null : view.summary().title())
            .label("Action", ApprovalView::action)
            .text("Assistant", ApprovalView::agent)
            .money("Amount (INR)", view -> view.summary() == null ? null : view.summary().amount())
            .label("Status", ApprovalView::status)
            .yesNo("Four-eyes", ApprovalView::fourEyes)
            .text("Requested by", ApprovalView::requestedBy)
            .dateTime("Expires", ApprovalView::expiresAt)
            .dateTime("Decided", ApprovalView::decidedAt)
            .dateTime("Executed", ApprovalView::executedAt)
            .label("Failure", view -> view.failure() == null ? null : view.failure().code())
            .text("Approval ID", ApprovalView::id)
            .build();
}
