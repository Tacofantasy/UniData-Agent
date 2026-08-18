# 采用原生 Function Calling 作为工具调用机制

在原生 Function Calling 和 Prompt Engineering 模拟之间，选择原生 FC。

理由：
1. ReAct 循环中 LLM 需要在"调用工具"和"给出最终答案"之间反复切换，原生 FC 是唯一可靠的方式——Prompt 模拟在多轮场景下 JSON 格式错误率会累积放大
2. 百炼 qwen3.7 系列模型支持 Function Calling，且所有 ChatClient 继承自 `AbstractOpenAIStyleChatClient`，协议改动集中在一个类中
3. 现有 `LLMMcpParameterExtractor`（用独立 LLM 调用提参）是 FC 不可用时的妥协方案，在 Agent 模式下应被原生 FC 替代

改造范围：
- `ChatRequest` 增加 `tools` 和 `tool_choice` 字段
- `ChatMessage.Role` 增加 `TOOL` 角色，`ChatMessage` 增加 `toolCalls` 和 `toolCallId` 字段
- `AbstractOpenAIStyleChatClient` 的 `buildRequestBody` / `extractChatContent` / `buildMessages` 增加 FC 支持
- `LLMService` 接口增加返回结构化响应（含 tool_calls）的能力
