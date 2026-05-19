-- 为 article 表添加 platform 字段（发布平台）
use multi_agent_creator;

ALTER TABLE article
    ADD COLUMN platform VARCHAR(30) NULL COMMENT '发布平台：wechat_official/xiaohongshu/weibo/default' AFTER topic;
