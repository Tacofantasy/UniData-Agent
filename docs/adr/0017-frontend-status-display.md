# 前端状态展示：当前阶段不做

Agent 工具调用过程中不向前端推送工具状态，用户只看到 loading。

后续演进（向前兼容）：在 StreamCallback 增加 `default void onToolStatus(String toolName, String status)` 方法，Agent 每次调用工具时推一个 SSE 状态事件，前端显示"正在检索知识库..."等提示。

理由：前端状态展示是纯 UX 优化，不影响 Agent 功能正确性，当前阶段应先跑通核心逻辑。
