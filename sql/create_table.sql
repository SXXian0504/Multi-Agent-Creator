# 数据库初始化（基础表结构）
# 注意：此文件只包含基础表结构，其他字段由增量 SQL 文件添加

-- 设置字符集（解决中文乱码问题）
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

-- 创建库
create database if not exists multi_agent_creator CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 切换库
use multi_agent_creator;

-- 用户表（基础字段，quota 和 vipTime 由增量脚本添加）
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
) comment '用户' collate = utf8mb4_unicode_ci;

-- 初始化数据
-- 密码是 12345678（MD5 加密 + 盐值 sxxian）
INSERT INTO user (id, userAccount, userPassword, userName, userAvatar, userProfile, userRole) VALUES
(1, 'admin', '4f0dab9f6c86d36f3107e3ab48bc2793', '管理员', '(NULL)', '系统管理员', 'admin'),
(2, 'user', '4f0dab9f6c86d36f3107e3ab48bc2793', '普通用户', '(NULL)', '我是一个普通用户', 'user'),
(3, 'test', '4f0dab9f6c86d36f3107e3ab48bc2793', '测试账号', '(NULL)', '这是一个测试账号', 'user');

-- 文章表（基础字段，style/phase/titleOptions/userDescription/enabledImageMethods 由增量脚本添加）
create table if not exists article
(
    id              bigint auto_increment comment 'id' primary key,
    taskId          varchar(64)                        not null comment '任务ID（UUID）',
    userId          bigint                             not null comment '用户ID',
    topic           varchar(500)                       not null comment '选题',
    platform        varchar(30)                        null comment '发布平台：default/wechat_official/xiaohongshu/weibo',
    knowledgeEnhanced tinyint default 0                null comment '是否允许知识库增强',
    useWritingStyleMemory tinyint default 0            null comment '是否使用写作风格记忆',
    knowledgeBaseIds json                              null comment '本次创作允许使用的知识库 ID 列表',
    wordRange       varchar(20)                        null comment '字数范围：short/medium/long',
    mainTitle       varchar(200)                       null comment '主标题',
    subTitle        varchar(300)                       null comment '副标题',
    outline         json                               null comment '大纲（JSON格式）',
    content         text                               null comment '正文（Markdown格式）',
    fullContent     text                               null comment '完整图文（Markdown格式，含配图）',
    coverImage      varchar(512)                       null comment '封面图 URL',
    images          json                               null comment '配图列表（JSON数组，包含封面图 position=1）',
    status          varchar(20) default 'PENDING'      not null comment '状态：PENDING/PROCESSING/COMPLETED/FAILED',
    errorMessage    text                               null comment '错误信息',
    createTime      datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    completedTime   datetime                           null comment '完成时间',
    updateTime      datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete        tinyint     default 0              not null comment '是否删除',
    UNIQUE KEY uk_taskId (taskId),
    INDEX idx_userId (userId),
    INDEX idx_status (status),
    INDEX idx_createTime (createTime),
    INDEX idx_userId_status (userId, status)
) comment '文章表' collate = utf8mb4_unicode_ci;

-- 智能体执行日志表
create table if not exists agent_log
(
    id              bigint auto_increment comment 'id' primary key,
    taskId          varchar(64)                        not null comment '任务ID',
    traceId         varchar(64)                        null comment '链路ID，阶段1默认与taskId一致',
    phase           varchar(50)                        null comment '执行阶段，见ArticlePhaseEnum',
    agentName       varchar(50)                        not null comment '智能体名称',
    startTime       datetime                           not null comment '开始时间',
    endTime         datetime                           null comment '结束时间',
    durationMs      int                                null comment '耗时（毫秒）',
    status          varchar(20)                        not null comment '状态：SUCCESS/FAILED',
    errorMessage    text                               null comment '错误信息',
    retryCount      int         default 0              null comment '当前阶段重试次数',
    prompt          text                               null comment '使用的Prompt',
    inputData       json                               null comment '输入数据（JSON格式）',
    outputData      json                               null comment '输出数据（JSON格式）',
    metadata        json                               null comment '扩展元数据',
    createTime      datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime      datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete        tinyint     default 0              not null comment '是否删除',
    INDEX idx_taskId (taskId),
    INDEX idx_traceId (traceId),
    INDEX idx_phase (phase),
    INDEX idx_agentName (agentName),
    INDEX idx_status (status),
    INDEX idx_createTime (createTime)
) comment '智能体执行日志表' collate = utf8mb4_unicode_ci;

-- 知识库表
create table if not exists knowledge_base
(
    id          bigint auto_increment comment 'id' primary key,
    userId      bigint                             not null comment '用户ID',
    name        varchar(120)                       not null comment '知识库名称',
    description varchar(500)                       null comment '描述',
    type        varchar(50)                        not null comment '知识库类型',
    status      varchar(20) default 'ACTIVE'       not null comment 'ACTIVE/DISABLED',
    createTime  datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint     default 0              not null comment '是否删除',
    INDEX idx_userId (userId),
    INDEX idx_userId_type (userId, type),
    INDEX idx_status (status)
) comment '知识库' collate = utf8mb4_unicode_ci;

-- 知识库文档表
create table if not exists knowledge_document
(
    id              bigint auto_increment comment 'id' primary key,
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
    INDEX idx_userId (userId),
    INDEX idx_knowledgeBaseId (knowledgeBaseId),
    INDEX idx_parseStatus (parseStatus)
) comment '知识库文档' collate = utf8mb4_unicode_ci;

-- 知识库索引任务表
create table if not exists knowledge_ingestion_job
(
    id           bigint auto_increment comment 'id' primary key,
    documentId   bigint                             not null comment '文档ID',
    status       varchar(20) default 'PENDING'      not null comment 'PENDING/PARSING/COMPLETED/FAILED',
    startedAt    datetime                           null comment '开始时间',
    finishedAt   datetime                           null comment '结束时间',
    errorMessage text                               null comment '错误信息',
    createTime   datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    INDEX idx_documentId (documentId),
    INDEX idx_status (status)
) comment '知识库索引任务' collate = utf8mb4_unicode_ci;
