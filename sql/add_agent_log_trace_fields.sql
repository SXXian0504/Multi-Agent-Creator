-- 阶段 1：为 agent_log 增加 trace 和阶段日志扩展字段

use multi_agent_creator;

ALTER TABLE agent_log
    ADD COLUMN traceId VARCHAR(64) NULL COMMENT '链路ID，阶段1默认与taskId一致' AFTER taskId,
    ADD COLUMN phase VARCHAR(50) NULL COMMENT '执行阶段，见ArticlePhaseEnum' AFTER traceId,
    ADD COLUMN retryCount INT DEFAULT 0 NULL COMMENT '当前阶段重试次数' AFTER errorMessage,
    ADD COLUMN metadata JSON NULL COMMENT '扩展元数据，承载阶段指标、工具结果、fallback等信息' AFTER outputData,
    ADD INDEX idx_traceId (traceId),
    ADD INDEX idx_phase (phase);
