# 采用 ReAct 循环作为 Agent 运行范式

项目从线性 WORKFLOW 管线演进为 Agent 架构。在 ReAct、单步路由、Plan-then-Execute 三种范式中，选择 ReAct 循环——LLM 在每轮中输出 Thought → Action → Observation，循环直到最终答案。

理由：
1. `OrchestrationMode.AGENT` 的原始设计注释已明确是"ReAct 架构"，与原作者意图一致
2. 现有 MCP 工具（如 sales_query 支持多维筛选）天然适合多轮调用场景
3. 工程化要素（Token 预算、工具调用真实性等）在 ReAct 范式下才有实际治理意义

代价：延迟更高、Token 消耗更大、流式输出实现更复杂。
