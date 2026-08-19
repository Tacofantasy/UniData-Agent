# ReAct 循环终止条件与兜底策略

## 最大循环轮数
可配置，通过 `unidata.agent.max-iterations` 配置，默认 5 轮。

## 超限兜底
强制合成——将已收集到的所有工具结果作为上下文，用一次额外的 LLM 调用强制生成最终答案。复用现有 `RAGPromptService.buildStructuredMessages` 能力。

理由：比中断告知体验好——用户至少能拿到基于已有信息的部分答案，而非超时白屏。

## 工具异常兜底
注入错误信息继续循环——将工具错误作为 Observation 喂回 LLM，让 LLM 自主决定重试、换工具或直接回答。

理由：ReAct 的核心优势是 LLM 能根据 Observation 自主调整策略。工具失败时 LLM 可能换工具、换参数重试、或基于已有信息回答，比粗暴中断更符合 Agent 设计哲学。
