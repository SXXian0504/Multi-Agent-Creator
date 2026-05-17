# 添加文章字数范围字段
use multi_agent_creator;

-- 为 article 表添加 wordRange 字段（用户创建时选择的字数范围）
ALTER TABLE article
    ADD COLUMN wordRange VARCHAR(20) NULL COMMENT '字数范围：short/medium/long，为空时由 Agent 自行评估' AFTER style;
