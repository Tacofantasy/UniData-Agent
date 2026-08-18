# Pipeline 路由策略

保留 WORKFLOW 模式代码不删除，但默认不启用。先实现 Agent 模式。

- `StreamChatPipeline`（WORKFLOW）代码保留，不改动
- 新建 `AgentChatPipeline` 实现 ReAct 循环
- `RAGChatServiceImpl` 中按 `OrchestrationMode` 分流：当前默认走 Agent，配置 `ragent.engine.type=workflow` 可回退到 WORKFLOW
- 两个 Pipeline 实现同一个 `ChatPipeline` 接口（`void execute(StreamChatContext ctx)`）

理由：保留 WORKFLOW 作为安全回退，降低改造风险。Agent 模式验证稳定后如不再需要 WORKFLOW 可后续删除。
