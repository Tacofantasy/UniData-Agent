# 并发工具调用：并行执行

LLM 一次返回多个 tool_call 时，使用 CompletableFuture 并行执行，等全部完成后将结果一起喂回 LLM 进入下一轮。

部分失败处理：成功的工具注入结果，失败的工具注入错误信息，一起喂回 LLM。

理由：复用现有 RetrievalEngine.executeMcpTools() 的 CompletableFuture 并行模式；串行多工具延迟不可接受。
