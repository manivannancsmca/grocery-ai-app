-- The MCP pilot (2026-11-20): how a tool call reached the business service - REST (through the gateway) or MCP (the
-- service's own MCP server: searchProducts on catalog-query-service, getOrderStatus on order-service). NULL for calls
-- that never reached a service (blocked, approval requests) and for every row written before this column.
-- Expand-only: code that does not know the column ignores it.
ALTER TABLE ai_tool_invocation
    ADD COLUMN transport VARCHAR(10) NULL AFTER page_links;
