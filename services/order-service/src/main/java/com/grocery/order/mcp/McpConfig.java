package com.grocery.order.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The MCP server's tools ({@link OrderMcpTools}); the server itself is Spring AI's ({@code spring.ai.mcp.server}). */
@Configuration(proxyBeanMethods = false)
public class McpConfig {

    @Bean
    ToolCallbackProvider orderMcpToolCallbacks(OrderMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
