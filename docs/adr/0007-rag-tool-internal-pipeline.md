# RAG Tool 内部管线设计

RAG Tool 内部执行 IntentResolver + RetrievalEngine，跳过 QueryRewrite。

## 保留
- **IntentResolver（KB 意图分类）**：决定查哪些知识库，是 RAG Tool 的核心内部能力
- **RetrievalEngine（多通道检索 + Rerank）**：检索引擎完整复用

## 跳过
- **QueryRewrite（查询改写）**：Agent 的 ReAct 循环天然包含问题理解和改写，LLM 在 Thought 中已分析用户意图，传入 RAG Tool 的 question 应已是精确检索 query。QueryRewrite 的指代消解能力由 Agent 消息列表中的对话历史覆盖。

## 废弃（Agent 模式下不运行）
- **MCP 意图分类**：MCP 工具作为独立 Agent Tool 暴露给 LLM，由 FC 直接调用，不再走意图分类路由
- **IntentGuidance（歧义检测+追问）**：Agent LLM 自主判断是否需要追问，不需要前置歧义检测
- **System 意图识别**：Agent LLM 自然处理闲聊/寒暄

理由：减少每次 RAG Tool 调用中的额外 LLM 调用（省去 QueryRewrite），同时保留 KB 路由能力。
