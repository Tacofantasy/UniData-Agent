# Agent 可观测性：复用现有 Trace 体系，扁平记录

复用现有 `RagTraceNodeDO` + `@RagTraceNode` AOP 体系，不修改数据模型和存储结构。

每轮 LLM 调用和工具调用作为独立 Trace Node 写入，通过 nodeName 标识轮次和类型，按时间排序即可还原推理链路。

新增 Node 类型：
- `AGENT_LLM_CALL`：每轮 LLM 调用（extraData 记录是否返回 tool_call、token 用量）
- `AGENT_TOOL_EXEC`：每次工具执行（extraData 记录 toolId、参数、耗时、成功/失败）
- `AGENT_FINAL_SYNTHESIS`：强制合成（如触发）

理由：
1. 不改数据模型，不改进查询逻辑，不改前端
2. `@RagTraceNode` AOP 注解可直接用在 AgentChatPipeline 方法上
3. 扁平记录虽无嵌套视觉效果，但轮次、工具、结果等信息完整可追溯
