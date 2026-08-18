# Ragent 上下文

Ragent 是一个企业级 RAG（检索增强生成）平台，正在从线性 WORKFLOW 管线演进为 Agent 架构，将 RAG 检索能力降级为 Agent 的一个 Tool。

## 语言

**编排模式（OrchestrationMode）**:
系统执行架构的档位，由 `ragent.engine.type` 配置，可选 `workflow`（线性管线）或 `agent`（ReAct 循环）。切换需重启。
_避免使用_: engine type, pipeline mode

**ReAct 循环**:
Agent 的运行范式——LLM 在每轮中输出 Thought（思考）→ Action（调用工具）→ Observation（观察结果），循环直到给出最终答案。
_避免使用_: agent loop, reasoning loop

**工作流管线（WORKFLOW 管线）**:
v1 编排架构——意图分类 → 检索 → 合成，链路确定、延迟低。由 `StreamChatPipeline` 实现。
_避免使用_: v1 pipeline, linear pipeline

**RAG Tool 内部管线**:
Agent 模式下 RAG Tool 的内部执行路径：IntentResolver → RetrievalEngine。跳过 QueryRewrite（Agent LLM 已做问题改写），废弃 MCP 意图分类和 IntentGuidance（由 Agent FC 和 LLM 自主推理替代）。
_避免使用_: rag pipeline, retrieval pipeline

**MCP 工具（MCP Tool）**:
通过 Model Context Protocol 暴露的可调用工具，由 MCP Server 提供、MCP Client 自动发现注册。现有：sales_query、ticket_query、weather_query、youcom_search。
_避免使用_: function, plugin

**RAG Tool**:
将 RAG 检索能力封装为 Agent 可调用的工具，是本次重构的核心产物。Agent 通过调用 RAG Tool 获取知识库检索结果。入参仅 question 一个字段，后端内部复用现有意图分类 → 多通道检索 → Rerank 管线，对 LLM 黑箱。
_避免使用_: KB tool, retrieval tool

**原生 Function Calling（Native FC）**:
LLM 原生支持的工具调用能力——模型在响应中直接返回 tool_calls（工具名 + 参数 JSON），后端解析后执行工具。区别于用 Prompt Engineering 模拟的方式。
_避免使用_: function calling, tool use, simulated FC

**对话记忆（Conversation Memory）**:
按 conversationId 关联的多轮对话历史，存储于 t_message 表。保留最近 N 轮原文，超出后触发摘要压缩。Agent 模式下只存最终答案和工具调用摘要，不存 ReAct 中间步骤。
_避免使用_: chat history, message log

**工具调用摘要（Tool Call Summary）**:
Agent 一次 ReAct 循环中所有工具调用的结构化摘要，记录调了什么工具、查到了什么关键信息。存入 t_message 表，作为对话记忆的一部分，供后续轮次回溯。
_避免使用_: tool log, action record

**Sources 累加器（SourcesAccumulator）**:
请求级 Spring Bean，RAG Tool 执行后将 sources 和 grounding chunks 存入其中，Agent 循环结束后统一取出推给前端。支持多次 RAG Tool 调用的累加。
_避免使用_: source collector, citation accumulator
