-- v1.1.0 260816 Agent 重构：t_message 增加 message_type 字段
-- 用于区分正常对话消息和工具调用摘要（ADR-0003）

ALTER TABLE t_message ADD COLUMN IF NOT EXISTS message_type VARCHAR(32) NOT NULL DEFAULT 'NORMAL';

COMMENT ON COLUMN t_message.message_type IS '消息类型：NORMAL=正常对话，TOOL_SUMMARY=工具调用摘要';
