package com.grocery.ai.tool;

import com.grocery.ai.gateway.CallContext;
import com.grocery.common.security.CurrentUser;

/**
 * One validated tool call, run as the caller.
 *
 * @param http      the caller's token and correlation ID, forwarded through the gateway
 * @param user      the caller
 * @param args      the validated arguments
 * @param transport which way the call reached the service (REST unless an MCP server answered), for the audit
 */
public record ToolCallContext(CallContext http, CurrentUser user, ToolArgs args, Transport transport) {

    public ToolCallContext(CallContext http, CurrentUser user, ToolArgs args) {
        this(http, user, args, new Transport());
    }
}
