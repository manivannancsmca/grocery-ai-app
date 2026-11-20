package com.grocery.ai.tool;

/**
 * Which way one tool call reached the business service, for the audit ({@code ai_tool_invocation.transport}): REST
 * through the gateway (every tool), or MCP (the pilot's tools, when their service's MCP server answered).
 */
public final class Transport {

    public static final String REST = "REST";
    public static final String MCP = "MCP";

    private volatile String used = REST;

    /** The call was answered by the service's MCP server. */
    public void usedMcp() {
        used = MCP;
    }

    public String used() {
        return used;
    }
}
