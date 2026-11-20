package com.grocery.ai.tool;

/** What ToolGuard does with a tool call that passed the checks (contract §3, step 4). */
public enum RiskClass {
    /** Runs. */
    READ,
    /** Runs (cart changes). */
    LOW_RISK_WRITE,
    /** Never runs: becomes an approval request a human decides. */
    SENSITIVE
}
