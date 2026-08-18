# ReAct 流式输出策略

中间轮同步调用，最终答案一次性返回。

- ReAct 循环中所有 LLM 调用使用 `llmService.chat()`（同步），不使用 `streamChat()`
- 每轮检查响应：如果返回 tool_calls 则执行工具继续循环；如果返回纯文本则说明已到最终答案，通过 `callback.onContent()` 一次性推送，然后 `callback.onComplete()`
- 不扩展 `StreamCallback`，不改动 `AbstractOpenAIStyleChatClient` 的流式解析逻辑

理由：
1. 最小工作量——不需要改流式 SSE 解析和 tool_calls 增量处理
2. ReAct 模式下用户本需等待思考+工具调用过程，最终答案的"卡顿"感可通过前端 loading 动画缓解
3. 后续如体验是痛点，可升级为全流式（扩展 StreamCallback 增加 onToolCallDelta），向前兼容
