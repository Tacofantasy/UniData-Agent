# Agent 评测：当前阶段依赖 Trace 人工分析

不新建 Agent 评测端点或自动化评测框架。依赖问题 12 已定的 Trace 体系，通过 Trace 页面人工分析 Agent 推理链路是否合理。

后续演进方向：
- 阶段 B：扩展 EvalController 增加 `/rag/agent-eval` 端点，返回完整推理链路供批量验证
- 阶段 C：构建评测集 + LLM-as-Judge 自动打分，做大规模回归测试

理由：当前阶段缺乏标注数据，自动化评测建了也跑不起来。先跑通 Agent 验证行为合理，再考虑评测。
