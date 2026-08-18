# RAG Tool 注册策略：复用 McpToolRegistry

RAG Tool 实现 `McpToolExecutor` 接口，注册为 Spring Bean，通过 `DefaultMcpToolRegistry` 的自动发现机制纳入统一管理。

理由：
1. `DefaultMcpToolRegistry` 已有 `@PostConstruct` 自动扫描所有 `McpToolExecutor` Bean 的机制，RAG Tool 加 `@Component` 即自动注册，零配置
2. Agent 只需从 `McpToolRegistry.listAllTools()` 获取全部工具定义（RAG + 远程 MCP），统一转换为 FC 格式传给 LLM
3. 工具执行统一走 `McpToolRegistry.getExecutor(toolId).execute(params)`，本地/远程对 Agent 透明
4. 工具数量少（5 个），不需要额外的抽象层

需新增一个 `McpSchema.Tool → OpenAI FC Tool` 的转换器（结构几乎一致，简单 mapping）。
