-- 阶段 8：RAG MVP 表结构
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;
use multi_agent_creator;

alter table article
    add column knowledgeEnhanced tinyint default 0 null comment '是否允许知识库增强',
    add column useWritingStyleMemory tinyint default 0 null comment '是否使用写作风格记忆',
    add column knowledgeBaseIds json null comment '本次创作允许使用的知识库 ID 列表';

create table if not exists knowledge_base
(
    id          bigint auto_increment primary key comment 'id',
    userId      bigint                             not null comment '用户ID',
    name        varchar(120)                       not null comment '知识库名称',
    description varchar(500)                       null comment '描述',
    type        varchar(50)                        not null comment '知识库类型',
    status      varchar(20) default 'ACTIVE'       not null comment 'ACTIVE/DISABLED',
    createTime  datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint     default 0              not null comment '是否删除',
    index idx_userId (userId),
    index idx_userId_type (userId, type),
    index idx_status (status)
) comment '知识库' collate = utf8mb4_unicode_ci;

create table if not exists knowledge_document
(
    id              bigint auto_increment primary key comment 'id',
    userId          bigint                             not null comment '用户ID',
    knowledgeBaseId bigint                             not null comment '知识库ID',
    fileName        varchar(255)                       not null comment '文件名',
    fileType        varchar(20)                        not null comment '文件类型',
    fileSize        bigint                             null comment '文件大小',
    storageUrl      varchar(1024)                      null comment '原始文件存储地址',
    parseStatus     varchar(20) default 'PENDING'      not null comment 'PENDING/PARSING/COMPLETED/FAILED',
    chunkCount      int         default 0              null comment 'chunk 数量',
    errorMessage    text                               null comment '错误信息',
    createTime      datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime      datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete        tinyint     default 0              not null comment '是否删除',
    index idx_userId (userId),
    index idx_knowledgeBaseId (knowledgeBaseId),
    index idx_parseStatus (parseStatus)
) comment '知识库文档' collate = utf8mb4_unicode_ci;

create table if not exists knowledge_ingestion_job
(
    id           bigint auto_increment primary key comment 'id',
    documentId   bigint                             not null comment '文档ID',
    status       varchar(20) default 'PENDING'      not null comment 'PENDING/PARSING/COMPLETED/FAILED',
    startedAt    datetime                           null comment '开始时间',
    finishedAt   datetime                           null comment '结束时间',
    errorMessage text                               null comment '错误信息',
    createTime   datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    index idx_documentId (documentId),
    index idx_status (status)
) comment '知识库索引任务' collate = utf8mb4_unicode_ci;

-- PostgreSQL / pgvector 侧执行：
-- CREATE EXTENSION IF NOT EXISTS vector;
-- CREATE TABLE IF NOT EXISTS knowledge_chunk (
--   id BIGSERIAL PRIMARY KEY,
--   user_id BIGINT NOT NULL,
--   knowledge_base_id BIGINT NOT NULL,
--   document_id BIGINT NOT NULL,
--   chunk_index INT NOT NULL,
--   content TEXT NOT NULL,
--   content_hash VARCHAR(64),
--   token_count INT,
--   metadata JSONB,
--   embedding VECTOR(1536),
--   create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
-- );
-- CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_scope ON knowledge_chunk(user_id, knowledge_base_id, document_id);
