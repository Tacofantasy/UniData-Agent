# Agent 来源引用：通过请求级共享累加器传递

RAG Tool 执行检索后，将 sources 和 grounding chunks 存入请求级共享累加器（request-scoped bean）。Agent ReAct 循环结束后，从累加器取出所有 sources，调用 `callback.onSources()` 和 `callback.onGroundingChunks()`。

支持多次 RAG Tool 调用的 sources 累加——不同轮次查不同问题，sources 合并。

理由：来源引用和推荐追问是 RAG 系统核心 UX，不应丢失。实现成本低——一个 request-scoped Bean 做累加器，RagSearchToolExecutor 往里存，AgentChatPipeline 循环结束后取出来调 callback。
