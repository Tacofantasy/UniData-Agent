# Agent System Prompt 风格

采用简洁指令式，不显式教 ReAct 格式。

`AGENT_MAIN` 槽位只包含基本规则：工具清单、何时使用、用完就回答。依赖原生 FC 模型自身的工具使用训练能力，不显式要求"Thought → Action → Observation"格式。

理由：
1. 原生 FC 模型已被训练过如何使用工具，不需要在 System Prompt 中教 ReAct 格式
2. 显式 ReAct 指令是为 Prompt Engineering 模拟场景设计的，与原生 FC 范式冗余
3. Prompt 存于数据库可编辑，先上线简洁版，后续按实际效果调整

Prompt 内容通过 `AgentPromptResolver.resolve(AgentPromptSlot.AGENT_MAIN)` 读取，前端管理页面可编辑。
