# Multi-Agent-Creator：基于 Spring AI + RAG 的多智能体图文创作平台

<div align="center">

</div>

## 🎯 项目简介

Multi-Agent-Creator 是针对自媒体内容创作中选题难、写作慢、配图成本高、风格不稳定等痛点，基于 **Spring AI Alibaba** 框架构建的大模型应用。系统采用按业务域切片的架构，将文章生成、图片规划执行、RAG 检索增强、写作风格 Skill 和评测能力拆分为清晰模块，在不改变 REST API、SSE 协议和数据库结构的前提下，支持从选题到图文内容生成的端到端创作闭环。

```
文章应用层: 任务生命周期 → 阶段状态落库 → SSE 事件推送
文章工作流: LegacyArticleWorkflow / OrchestratedArticleWorkflow 双路径编排
文章 Agent: 标题、大纲、正文生成
图片模块: ImageAgent 规划/重规划 → ImageToolExecutionService 执行/评审/重试/fallback
RAG 模块: 文档解析切片 → embedding 索引 → 检索决策 → 上下文拼装
Skill 模块: 平台、文章风格、评审标准和提示词片段配置化
```

## 🎨 用户界面展示

以下是应用的核心界面流程展示：

### 1. 首页界面
应用的主页面，提供清晰的功能导航和快速入口：

![首页界面](asserts/HomePage.png)

### 2. 主题输入
用户可以在此页面输入想要创作的文章主题，例如"程序员如何提升竞争力"：

![主题输入](asserts/Title.png)

### 3. 标题生成
系统基于用户输入的主题，自动生成多个备选标题供用户选择：

![标题备选](asserts/Title2.png)

### 4. 大纲生成
根据用户选择的主题、标题以及补充的提示词，系统生成结构化的文章大纲：

![文章大纲](asserts/Outline.png)

### 5. 文章生成
最终生成的完整文章，包含丰富的配图和格式化的内容：

![文章详情](asserts/Article.png)

## 🎯 核心技术价值

| 特性 | 说明  |
|------|------|
| 🤖 Multi-Agent 协同 | 文章生成 Agent、图片规划 Agent、ReviewAgent 与 workflow 编排协作 |
| 📚 RAG 增强生成 | 文档解析、切片、embedding、pgvector 检索和上下文拼装 |
| 🎨 多模态配图 | 多种配图策略 + 图片规划/执行/评审/重规划闭环 |
| 🧩 写作风格 Skill | 平台、风格、提示词片段和评审标准配置化 |
| ⚡ 异步并行处理 | 文章任务异步执行、SSE 阶段推送、正文与图片路径并行 |
| 🐳 云原生架构 | Docker容器化 + 微服务设计  |

## ✨ 核心功能特性

### Multi-Agent 与 Workflow 架构

| 模块 | 职责 | 技术特点 |
|--------|------|----------|
| `ArticleGenerationApplicationService` | 文章任务入口、阶段状态、落库、SSE 推送 | Controller 只依赖应用层 |
| `LegacyArticleWorkflow` | 保留原 baseline 生成路径 | 兼容原有生成行为 |
| `OrchestratedArticleWorkflow` | 编排型生成路径 | 支持正文和图片并行、fallback 后继续产出 |
| `ArticleAgent` | 标题、大纲、正文生成 | RAG 上下文 + 结构化输出 |
| `ImageAgent` | 图片规划和失败重规划 | 根据正文、章节和 review observation 生成图片 requirement |
| `ReviewAgent` | 标题、大纲、正文、配图计划、图片结果评审 | 质量评分、问题定位、改进建议 |
| `ContentMergeService` | 图文合成 | 确定性内容服务，不作为 Agent |

### 多模态配图系统（Tool Calling + 策略模式）

基于Tool Calling实现动态配图服务调度，支持多种配图策略的智能选择与自动降级：

| 配图策略 | 技术实现 | 适用场景 |
|----------|--------|----------|
| Pexels图库 | 语义检索 + 关键词匹配 | 通用真实图片 |
| 国内图片检索 | 关键词检索 + 图片抽取 | 本土语境配图 |
| 万相文生图 | 百炼 `wanx-v1` 文生图 | 封面、创意插画、抽象概念、信息图 |
| Graphviz图表 | DOT 渲染 | 架构图、流程图、关系图 |
| Mermaid图表 | Mermaid 渲染 | 流程/架构辅助图 |
| Iconify图标 | 图标库检索 | 装饰性图标 |
| 表情包搜索 | Bing图片检索 | 情绪化配图 |
| SVG示意图 | AI概念图生成 | 技术示意图 |
| Picsum降级 | 随机图片服务 | 容错保障 |

> 图片生成由 `ImageAgent` 负责规划和重规划，`ImageToolExecutionService` 负责执行、评审、重试和 fallback；具体外部能力放在 `image.adapter`。

### RAG增强生成系统

当前 RAG 按 ingestion、retrieval、persistence 三层组织，文章生成侧只通过 `RagService` 获取检索上下文：

- **文档摄取**：上传文档后解析文本、切片、生成 embedding，并写入 pgvector 索引
- **检索决策**：根据文章主题、平台、风格、用户补充描述和知识库配置判断是否检索
- **向量检索**：通过 `PgVectorKnowledgeRepository` 封装建表、写入、删除和相似度检索
- **上下文拼装**：将召回片段整理为 `retrievedContext`，注入标题、大纲和正文生成上下文
- **降级策略**：RAG 失败时降级为普通创作，不中断文章生成流程

### 写作风格 Skill

写作风格 Skill 用于把平台、文章风格、提示词片段和评审标准从代码中抽离出来，作为文章生成和评审的可配置上下文：

- **平台配置**：适配不同内容平台的表达偏好和结构要求
- **风格配置**：控制文章语气、表达密度、专业程度和段落组织
- **提示词片段**：复用标题、大纲、正文、配图计划和评审提示词
- **评审标准**：为 `ReviewAgent` 提供结构、相关性、风格匹配等质量维度
- **阶段复用**：`ArticleAgent`、`ImageAgent`、`ReviewAgent` 在不同阶段读取统一 Skill 配置

### 异步处理与并行生成

文章生成由 `ArticleGenerationApplicationService` 作为应用层入口，通过 `articleExecutor` 异步执行各阶段任务；`ArticleGenerationEventPublisher` 负责把内部 workflow 消息转换为现有 SSE 消息类型。

| 阶段 | 处理方式 | 用户体验 |
|------|----------|----------|
| 标题生成 | 异步任务 + 结构化输出 + 评审 | 生成多个候选标题并等待用户确认 |
| 大纲生成 | 异步任务 + 流式输出 + 评审 | 生成结构化大纲并等待用户确认 |
| 正文生成 | workflow 执行，orchestrated 路径支持并行 | 正文内容持续推送 |
| 图片规划 | `ImageAgent` 输出图片 requirement | 前端可观察配图计划 |
| 图片执行 | 多图片并行执行、单图评审、失败重规划、fallback | 单张图片完成后逐步推送 |
| 图文合成 | `ContentMergeService` 确定性合成 | 输出最终 Markdown 图文内容 |

## 🚀 快速开始

### 环境要求

- JDK 21+（推荐OpenJDK 21）
- Node.js 18+（推荐LTS版本）
- MySQL 8.0+（支持PGVector扩展）
- Redis 7.x（用于缓存和配额管理）

### 1. 数据库初始化与配置

```bash
# 初始化基础表结构
mysql -uroot -p < sql/create_table.sql

# 执行增量更新脚本
mysql -uroot -p < sql/update_quota.sql
mysql -uroot -p < sql/add_phase_fields.sql
mysql -uroot -p < sql/add_article_style.sql
mysql -uroot -p < sql/add_rag_tables.sql
```

### 2. 环境配置

```bash
# 复制配置文件模板
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

编辑 `application-local.yml`，配置核心服务：

```yaml
# AI服务配置（必需）
spring:
  ai:
    dashscope:
      api-key: your-dashscope-api-key  # 通义千问API

# 配图服务配置（必需）
pexels:
  api-key: your-pexels-api-key  # Pexels图库API

# 百炼万相文生图配置（可选）
qwen:
  image:
    api-key: your-dashscope-api-key
    model: wanx-v1

tencent:
  cos:
    secret-id: xxx  # 腾讯云对象存储
    secret-key: xxx
    region: ap-guangzhou
    bucket: your-bucket-name

# RAG向量数据库配置（可选）
pgvector:
  host: localhost
  port: 5432
  database: vector_db
  username: postgres
  password: password
```

### 3. 启动后端服务

```bash
# 编译并启动
mvn clean compile spring-boot:run

# 或使用开发模式（热重载）
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

API文档地址：http://localhost:8567/api/doc.html

### 4. 启动前端服务

```bash
cd ./multi-agent-creator/

# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 或构建生产版本
npm run build
npm run preview
```

前端访问地址：http://localhost:5173

## 🐳 容器化部署（生产推荐）

### 前置条件

- Docker 20.10+（支持BuildKit）
- Docker Compose v2.20+（支持Profiles）

### 一键部署流程

```bash
# 1. 配置环境变量
cp .env.example .env

# 2. 编辑配置文件（必需配置AI服务）
vim .env

# 3. 使用智能启动脚本
./start.sh

# 或直接使用Docker Compose
docker compose up -d --build
```


### 服务架构与端口

| 服务组件 | 端口 | 访问地址 | 说明 |
|----------|------|----------|------|
| 前端服务 | 80 | http://localhost | 用户界面 |
| 后端API | 8123 | http://localhost:8123/api | RESTful API |
| API文档 | 8123 | http://localhost:8123/api/doc.html | Swagger UI |
| 健康检查 | 8123 | http://localhost:8123/api/health | 服务状态 |


### 环境变量配置详解

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| DASHSCOPE_API_KEY | - | 通义千问与万相文生图服务密钥 |
| PEXELS_API_KEY  | - | Pexels图库API密钥 |
| MYSQL_ROOT_PASSWORD  | 123456 | 数据库root密码 |
| REDIS_PASSWORD  | - | Redis访问密码 |
| TENCENT_COS_SECRET_ID  | - | 腾讯云COS密钥ID |

**生产环境建议**：
- 使用Docker Secrets管理敏感信息
- 配置HTTPS反向代理（Nginx/Traefik）
- 启用Prometheus + Grafana监控
- 配置日志聚合（ELK Stack）

## 📁 系统架构设计

### 后端架构结构

后端按业务域切片组织。文章、图片、RAG 等核心业务代码放在各自领域包内，`controller`、`service`、`model`、`mapper` 等通用层短期保留，公共 DTO/VO/Entity 后续再按引用稳定情况逐步迁移。

```text
src/main/java/com/sxxian/multiagentcreator/
├── article/                         # 文章生成域
│   ├── application/                  # 应用层入口、任务生命周期、SSE 事件发布
│   │   ├── ArticleGenerationApplicationService.java
│   │   └── ArticleGenerationEventPublisher.java
│   ├── workflow/                     # 文章生成流程编排
│   │   ├── OrchestratedArticleWorkflow.java
│   │   ├── ContentMergeService.java
│   │   └── legacy/
│   │       └── LegacyArticleWorkflow.java
│   ├── agent/                        # 真正 LLM 决策型文章 Agent
│   │   └── ArticleAgent.java
│   └── review/                       # 内容、配图计划、图片结果评审
│       └── ReviewAgent.java
├── image/                           # 图片域
│   ├── planning/                     # 图片规划与失败重规划
│   │   └── ImageAgent.java
│   ├── execution/                    # 图片 requirement 执行、评审、重试、fallback
│   │   ├── ImageToolExecutionService.java
│   │   └── ImageGenerationTool.java
│   └── adapter/                      # 具体图片/上传能力适配器
│       ├── ImageServiceStrategy.java
│       ├── PexelsService.java
│       ├── QwenImageService.java
│       ├── GraphvizService.java
│       ├── MermaidService.java
│       ├── IconifyService.java
│       ├── CosService.java
│       └── ...
├── rag/                             # 知识库增强域
│   ├── ingestion/                    # 文档解析、切片、embedding、索引写入
│   ├── retrieval/                    # 检索决策、向量检索、上下文拼装
│   │   └── RagService.java
│   └── persistence/                  # pgvector repository
├── eval/                            # 评测域
├── agent/                           # Agent 基础设施：config/context/parallel/保留节点
├── annotation/                      # 自定义注解
├── aop/                             # 切面
├── config/                          # 系统配置
├── controller/                      # REST API 控制器，对外路径保持不变
├── exception/                       # 异常处理
├── manager/                         # SSE 等连接管理
├── mapper/                          # 数据访问层
├── model/                           # DTO/Entity/Enum/VO，阶段性保留共享模型
├── service/                         # 仍未下沉到领域包的通用/存量服务
└── utils/                           # 工具类
```

文章生成入口统一为 `ArticleGenerationApplicationService`。它根据配置选择 `LegacyArticleWorkflow` 或 `OrchestratedArticleWorkflow`，Controller 不直接依赖具体 workflow 或 agent。

SSE 协议由 `ArticleGenerationEventPublisher` 统一转换和发送，消息类型继续使用现有 `SseMessageTypeEnum`，REST API 路径和数据库结构保持兼容。

### 前端架构结构

```
├── multi-agent-creator/
│   ├── src/
│   │   ├── pages/                       # 页面组件
│   │   │   ├── ArticleCreatePage.vue      # 文章创作页面
│   │   │   ├── ArticleDetailPage.vue      # 文章详情页面
│   │   │   ├── ArticleListPage.vue        # 文章列表页面
│   │   │   └── admin/                    # 管理后台页面
│   │   ├── components/                  # 公共组件
│   │   │   ├── article/                  # 文章相关组件
│   │   │   │   ├── CreatingState.vue      # 创作状态组件
│   │   │   │   ├── InputState.vue         # 输入状态组件
│   │   │   │   └── CompletedState.vue    # 完成状态组件
│   │   │   └── StatusBadge.vue          # 状态标识组件
│   │   ├── api/                         # API接口层
│   │   │   ├── articleController.ts      # 文章API
│   │   │   ├── userController.ts         # 用户API
│   │   │   └── knowledgeBaseController.ts # 知识库API
│   │   ├── stores/                      # 状态管理
│   │   │   └── loginUser.ts              # 用户状态管理
│   │   ├── utils/                       # 工具函数
│   │   │   ├── sse.ts                    # SSE通信工具
│   │   │   ├── article.ts                # 文章处理工具
│   │   │   └── markdown.ts               # Markdown处理工具
│   │   └── router/                      # 路由配置
│   │       └── index.ts                # 路由定义
│   └── package.json                   # 依赖配置
```

### 数据库设计

```
├── sql/                                # 数据库脚本
│   ├── create_table.sql               # 基础表结构
│   ├── update_quota.sql               # 配额管理更新
│   ├── add_phase_fields.sql           # 阶段字段更新
│   ├── add_article_style.sql          # 文章风格更新
│   └── add_rag_tables.sql             # RAG知识库表结构
```

## 🗄 数据模型设计

### 核心业务表结构

| 表名 | 功能描述 | 关键特性 |
|------|----------|----------|
| user | 用户账户管理 | 登录身份、创作配额、权限控制 |
| article | 文章生命周期管理 | 多阶段状态、配图策略 |
| agent_log | 智能体执行追踪 | 性能监控、错误诊断、优化分析 |
| knowledge_base | 知识库元数据 | 风格记忆、资料库、用户隔离 |
| knowledge_document | 知识库文档 | 上传状态、解析状态、切片统计 |
| knowledge_ingestion_job | 文档索引任务 | 解析、切片、embedding、索引写入状态 |

### 文章表核心字段设计

```sql
-- 任务标识与状态管理
taskId               VARCHAR(64)     -- 全局唯一任务ID（UUID）
phase                VARCHAR(20)     -- 创作阶段：TITLE_SELECTION/OUTLINE_EDITING/CONTENT_GENERATION/COMPLETED
status               VARCHAR(20)     -- 执行状态：PENDING/PROCESSING/COMPLETED/FAILED

-- 内容结构化存储
topic                VARCHAR(500)    -- 创作选题
mainTitle            VARCHAR(200)    -- 主标题
subTitle             VARCHAR(300)    -- 副标题
outline              JSON            -- 结构化大纲（JSON格式）
content              TEXT            -- Markdown正文内容
fullContent          TEXT            -- 完整图文内容（含配图）

-- 多模态配图系统
coverImage           VARCHAR(512)    -- 封面图片URL
images               JSON            -- 配图列表（结构化JSON）
enabledImageMethods  JSON            -- 启用的配图策略（数组）

-- RAG增强字段
ragContext           JSON            -- RAG检索上下文
vectorEmbedding      VECTOR(1536)    -- 内容向量嵌入（PGVector）

-- 创作过程追踪
style                VARCHAR(50)     -- 文章风格类型
titleOptions         JSON            -- 候选标题列表
userDescription      TEXT            -- 用户补充描述
createTime           DATETIME        -- 创建时间
completedTime        DATETIME        -- 完成时间
```

### 智能体执行日志表

```sql
-- 执行追踪
taskId               VARCHAR(64)     -- 关联任务ID
agentName            VARCHAR(50)     -- 智能体标识
startTime            DATETIME        -- 执行开始时间
endTime              DATETIME        -- 执行结束时间
durationMs           INT             -- 执行耗时（毫秒）

-- 执行结果
status               VARCHAR(20)     -- 执行状态：SUCCESS/FAILED
errorMessage         TEXT            -- 错误详情
retryCount           INT             -- 重试次数

-- AI交互数据
prompt               TEXT            -- 输入的Prompt
tokensUsed           INT             -- Token使用量
inputData            JSON            -- 输入参数
outputData           JSON            -- 输出结果

-- RAG相关
retrievalQuery       TEXT            -- 检索查询
retrievedContexts    JSON            -- 检索到的上下文
contextRelevance     FLOAT           -- 上下文相关性评分
```

## 🔑 第三方服务配置

### 必需服务配置

| 服务名称 | 获取方式 | 配置说明 | 性能要求 |
|----------|----------|----------|----------|
| 通义千问(DashScope) | [阿里云百炼平台](https://bailian.console.aliyun.com) | 用于Multi-Agent协同生成 | QPS≥10，延迟<2s |
| 万相文生图 | [阿里云百炼平台](https://bailian.console.aliyun.com) | QWEN_IMAGE / wanx-v1 图片生成 | 按图片生成任务异步轮询 |
| Pexels API | [Pexels开发者平台](https://www.pexels.com/api/) | 高质量图库检索服务 | 成功率≥95% |

### 可选增强服务

| 服务名称 | 获取方式 | 功能特性 | 适用场景 |
|----------|----------|----------|----------|
| 腾讯云COS | [腾讯云控制台](https://console.cloud.tencent.com) | 图片存储、CDN加速 | 生产环境部署 |
| PGVector | [PostgreSQL扩展](https://github.com/pgvector/pgvector) | 向量检索、RAG增强 | 内容质量优化 |

### 服务配置最佳实践

```yaml
# 生产环境推荐配置
spring:
  ai:
    alibaba:
      dashscope:
        api-key: ${DASHSCOPE_API_KEY}
        timeout: 30s
        max-tokens: 4096

# 高可用配置建议
service:
  retry:
    max-attempts: 3
    backoff-delay: 1000ms
  circuit-breaker:
    failure-threshold: 5
    timeout: 10s
```

## 🧪 预置测试环境

### 测试账号体系

| 账号 | 密码 | 权限级别 | 配额限制 | 特殊功能 |
|------|------|----------|----------|----------|
| admin | 12345678 | 系统管理员 | 无限制 | 用户管理、数据统计 |
| creator | 12345678 | 高级创作者 | 100篇/月 | 多配图方式、RAG增强 |
| user | 12345678 | 普通用户 | 10篇/月 | 基础创作能力 |
| demo | 12345678 | 演示账号 | 3篇/月 | 功能体验限制 |

### 测试数据说明

**初始数据包含**：
- 5篇示例文章（不同风格）
- 完整的智能体执行日志
- 用户配额和知识库示例数据

## 🏛 核心架构设计

### Multi-Agent 与 Workflow 协同架构

系统将“谁做决策”和“谁做流程编排”拆开：Agent 只承担 LLM 决策，workflow 负责阶段顺序、并行路径和 fallback，application service 负责任务生命周期和对外事件。

**架构特点**：
- **应用层收口**：`ArticleGenerationApplicationService` 是文章任务唯一入口
- **双路径保留**：`LegacyArticleWorkflow` 保留 baseline，`OrchestratedArticleWorkflow` 承载编排路径
- **Agent 职责收敛**：`ArticleAgent`、`ImageAgent`、`ReviewAgent` 只处理 LLM 决策
- **确定性服务下沉**：`ContentMergeService`、`ImageToolExecutionService` 不再作为 Agent 命名
- **错误恢复**：图片执行支持单图重试、重规划和 fallback；RAG 失败降级为普通创作

**执行流程**：
1. `ArticleGenerationApplicationService` 初始化任务状态并选择 workflow。
2. `ArticleAgent` 依次生成标题、大纲、正文，并通过 `ReviewAgent` 评审。
3. orchestrated 路径中，正文生成和图片规划/执行可并行推进。
4. `ImageAgent` 生成图片 requirement，`ImageToolExecutionService` 执行、评审、重试和 fallback。
5. `ContentMergeService` 将正文与图片结果合成为最终 Markdown。

### RAG增强生成架构

RAG 模块按 ingestion、retrieval、persistence 拆分，文章生成侧只依赖 `RagService`：

**核心组件**：
- **`rag.ingestion`**：文档解析、切片、embedding、索引写入
- **`rag.retrieval`**：检索决策、向量检索调度、上下文拼装
- **`rag.persistence`**：pgvector 建表、写入、删除和检索封装
- **`RagService`**：文章生成侧唯一入口，输入 Article，输出 retrievedContext

### 多模态配图系统（Tool Calling）

图片模块拆成 planning、execution、adapter 三层：

**核心特性**：
- **规划/重规划**：`ImageAgent` 根据文章内容生成图片 requirement，并根据评审 observation 重规划
- **执行闭环**：`ImageToolExecutionService` 负责工具调用、图片评审、重试和 fallback
- **Adapter 隔离**：Pexels、万相文生图、Graphviz、Mermaid、Iconify、COS 等能力在 `image.adapter`
- **并行处理**：多张图片可并行执行，单张失败不阻塞整体 fallback

**配图策略**：
- 图库检索（Pexels、国内图片检索）
- AI生成（万相文生图、SVG Diagram等）
- 结构化图表（Graphviz、Mermaid）
- 图标检索（Iconify）
- 情感化配图（表情包搜索）

### 异步处理与并行架构

基于应用层异步任务、workflow 并行路径和 SSE 事件发布器实现实时生成反馈：

**技术特点**：
- **异步入口**：`executePhase1/2/3` 通过 `articleExecutor` 执行，避免阻塞请求线程
- **事件发布**：`ArticleGenerationEventPublisher` 统一转换 workflow 消息和 review 消息
- **并行路径**：orchestrated workflow 支持正文和图片链路并行推进
- **连接管理**：`SseEmitterManager` 管理长连接和完成/失败通知
- **错误处理**：阶段失败统一更新文章状态、推送 `ERROR` 消息并关闭 SSE

**性能优化**：
- 图片任务按 requirement 并发执行
- 单图失败进入重规划或 fallback，不阻断其它图片
- RAG 检索失败降级为空上下文，不阻断文章生成

### 系统性能特性

- **并行处理**：配图生成采用线程池并行执行
- **连接池优化**：HikariCP连接池配置，支持高并发访问
- **异步执行**：文章阶段任务和文档索引任务均通过线程池异步执行

## 🔧 系统扩展指南

### 添加新的配图策略

#### 1. 定义配图方式枚举

在 `ImageMethodEnum` 中添加新的配图方式，配置相关属性：
- 配图方式编码和描述
- 是否为AI生成类型
- 是否为降级备选方案
- 所需用户权限等级

#### 2. 实现配图服务接口

创建新的配图服务类，实现标准接口：
- 实现图片搜索核心逻辑
- 配置服务优先级和可用性检查
- 添加完善的错误处理和日志记录
- 支持第三方API集成和响应格式转换

#### 3. 配置自动注册

系统支持自动发现和注册新的配图服务：
- 基于Spring注解的自动配置
- 服务映射和策略选择器集成
- 支持热插拔和动态加载
