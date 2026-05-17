# Multi-Agent-Creator 架构设计

## 1. 设计目标

本架构面向图文创作场景，将当前固定生成流程升级为具备阶段状态、结构化输出校验、质量评审、用户反馈、工具重规划和过程追踪能力的 Agent 系统。

第一版遵循最小 MVP 原则：先把“生成 -> 校验 -> 评审 -> 用户确认 -> 再生成/重规划”的闭环跑通，再逐步接入 MCP、RAG、离线评测等增强能力。

## 2. 架构原则

1. 不以 Agent 数量作为复杂度来源，而以职责边界、状态管理和反馈闭环作为 Agent 化依据。
2. 文本创作统一收敛到 `ArticleAgent`，避免标题、大纲、正文被过度拆分成多个弱 Agent。
3. 图片生成由 `ImageAgent` 负责规划和重规划，具体工具执行交给 `ImageToolExecutor`。
4. 所有关键结构化输出都经过 `JsonStructuredOutputService` 解析、校验、修复和重试。
5. `ReviewAgent` 不直接替代业务规则，而是负责语义质量判断和改进建议。
6. 所有阶段必须可观测、可恢复、可回放。
7. RAG、MCP、AgentEval 是后续增强模块，不能阻塞第一版闭环交付。

## 3. 总体模块

| 模块 | 类型 | 核心职责 | MVP 是否必须 |
| --- | --- | --- | --- |
| `ArticleAgent` | Agent | 生成标题、大纲、正文；接收用户反馈后重写当前阶段 | 是 |
| `ImageAgent` | Agent | 生成配图计划；根据评审 observation 重规划图片工具和参数 | 是 |
| `ReviewAgent` | Agent | 评审标题、大纲、正文、配图计划、图片结果 | 是 |
| `JsonStructuredOutputService` | 基础服务 | JSON 提取、解析、Schema 校验、业务校验、修复、重试、指标记录 | 是 |
| `ImageToolExecutor` | 工具执行层 | 执行 Pexels、AI 生图、Mermaid、Iconify、SVG 等工具 | 是 |
| `AgentLogService` | 基础设施 | 记录 Agent、阶段、工具、评审、重试、异常和耗时 | 是 |
| `SkillService` | 配置服务 | 管理写作风格、文章类型、提示词片段和评审标准 | 是 |
| `GenerationTraceService` | 查询服务 | 按任务回放完整生成过程 | 建议 MVP 支持基础版 |
| `KnowledgeBaseService` | RAG 服务 | 用户知识库、文档解析、向量检索、上下文构建 | 第二阶段 |
| `McpToolGateway` | 工具协议层 | 将图片工具标准化为 MCP 工具接口 | 第二阶段 |
| `AgentEval` | 离线评测 | 固定主题集评测质量、稳定性、耗时、工具成功率 | 第三阶段 |

## 4. 主流程

```text
用户输入主题和写作要求
  -> ArticleAgent 生成标题候选
  -> JsonStructuredOutputService 校验标题 JSON
  -> ReviewAgent 评审标题候选
  -> Human-in-the-loop 用户选择标题或补充反馈

  -> ArticleAgent 生成大纲
  -> JsonStructuredOutputService 校验大纲 JSON
  -> ReviewAgent 评审大纲
  -> Human-in-the-loop 用户确认大纲或补充反馈

  -> ArticleAgent 生成正文 Markdown
  -> ContentValidator 校验正文格式和大纲覆盖度
  -> ReviewAgent 评审正文
  -> Human-in-the-loop 用户确认正文或补充反馈

  -> ImageAgent 生成配图计划
  -> JsonStructuredOutputService 校验 ImagePlan JSON
  -> ImageToolExecutor 执行图片工具
  -> ReviewAgent 评审图片结果
  -> 不通过：输出 observation 和 revision advice
  -> ImageAgent 基于 observation 重规划
  -> 通过：合成图文

  -> 用户最终确认
  -> 持久化文章、素材和生成过程日志
```

## 5. 阶段状态

文章任务需要显式维护阶段状态，支撑用户中断、继续、重试、回放和前端展示。

| 状态 | 说明 |
| --- | --- |
| `TITLE_GENERATING` | 标题生成中 |
| `TITLE_REVIEWING` | 标题评审中 |
| `TITLE_WAITING_USER_CONFIRM` | 等待用户选择或反馈标题 |
| `OUTLINE_GENERATING` | 大纲生成中 |
| `OUTLINE_REVIEWING` | 大纲评审中 |
| `OUTLINE_WAITING_USER_CONFIRM` | 等待用户确认或反馈大纲 |
| `CONTENT_GENERATING` | 正文生成中 |
| `CONTENT_REVIEWING` | 正文评审中 |
| `CONTENT_WAITING_USER_CONFIRM` | 等待用户确认或反馈正文 |
| `IMAGE_PLANNING` | 配图计划生成中 |
| `IMAGE_EXECUTING` | 图片工具执行中 |
| `IMAGE_REVIEWING` | 图片结果评审中 |
| `IMAGE_REPLANNING` | 图片结果未通过，重新规划中 |
| `MERGING` | 图文合成中 |
| `COMPLETED` | 全流程完成 |
| `FAILED` | 任务失败 |

## 6. ArticleAgent

`ArticleAgent` 负责所有文本创作阶段，但内部保留清晰的方法边界，方便 AOP 记录每个阶段的耗时、异常、成功率和重试次数。

```java
@AgentExecution(value = "article_generate_titles", phase = "TITLE")
List<TitleOption> generateTitles(ArticleContext context);

@AgentExecution(value = "article_generate_outline", phase = "OUTLINE")
OutlineResult generateOutline(ArticleContext context);

@AgentExecution(value = "article_generate_content", phase = "CONTENT")
String generateContent(ArticleContext context);
```

### 输入上下文

`ArticleContext` 至少包含：

| 字段 | 说明 |
| --- | --- |
| `taskId` | 当前文章任务 ID |
| `topic` | 用户输入主题 |
| `userRequirement` | 用户补充要求 |
| `selectedTitle` | 已确认标题 |
| `outline` | 已确认大纲 |
| `currentDraft` | 当前正文草稿 |
| `feedbackHistory` | 用户反馈历史 |
| `skillConfig` | 当前写作 Skill |
| `retrievedContext` | 可选 RAG 上下文 |
| `reviewHistory` | 评审历史摘要 |

### 输出策略

| 阶段 | 输出 | 是否强制 JSON |
| --- | --- | --- |
| 标题 | `TitleOptions` | 是 |
| 大纲 | `OutlineResult` | 是 |
| 正文 | Markdown | 否 |

正文最终交付形态是 Markdown，第一版不强制包成 JSON，避免长文本转义、流式输出和解析复杂度过高。正文阶段通过格式校验、大纲覆盖校验和 `ReviewAgent` 评审保证质量。

## 7. ImageAgent

`ImageAgent` 负责配图规划和失败后的重规划。

### ImagePlan 输出

```json
{
  "imageRequirements": [
    {
      "position": 1,
      "type": "cover",
      "sectionTitle": "",
      "imageSource": "QWEN_IMAGE",
      "keywords": "",
      "prompt": "A clean technical article cover...",
      "placeholderId": "{{IMAGE_PLACEHOLDER_1}}",
      "reason": "封面需要抽象表达技术主题，真实图库难以准确匹配",
      "retryCount": 0
    }
  ]
}
```

### 图片闭环

```text
ImageAgent 生成配图计划
  -> ImageToolExecutor 执行工具
  -> ReviewAgent 评审图片结果
  -> 不通过：ReviewAgent 输出 observation + revision advice
  -> ImageAgent 根据 observation 重规划
  -> 最多重试 N 次
  -> 仍失败：fallback 或交给用户确认
```

### 工具选择规则

| 内容类型 | 推荐工具 |
| --- | --- |
| 技术流程、架构关系、时序关系 | `MERMAID` / `SVG_DIAGRAM` |
| 封面、抽象概念、创意表达 | `QWEN_IMAGE` / 其他 AI 生图 |
| 真实场景、人物、办公、自然风景 | `PEXELS` |
| 小图标、装饰符号、列表视觉元素 | `ICONIFY` |
| 工具不可用或多次失败 | `PICSUM` fallback |

## 8. ReviewAgent

`ReviewAgent` 统一负责语义质量评审，不负责基础格式校验。

### 通用输出

```json
{
  "approved": false,
  "score": 74,
  "dimensionScores": {
    "relevance": 85,
    "clarity": 76,
    "structure": 70,
    "styleMatch": 65
  },
  "problems": [
    "第三节和第四节边界重叠"
  ],
  "suggestions": [
    "第三节聚焦方法，第四节聚焦案例"
  ],
  "nextAction": "REVISE"
}
```

### 通过阈值

| 分数 | 策略 |
| --- | --- |
| `>= 80` | 通过 |
| `70-79` | 不通过，进入一次重写或重规划 |
| `< 70` | 明显失败，优先重写、换工具或 fallback |

MVP 阶段统一使用 80 分作为通过阈值。

## 9. JsonStructuredOutputService

`JsonStructuredOutputService` 负责所有结构化输出的稳定性。

```text
模型原始输出
  -> 提取 JSON
  -> Gson/Jackson 解析为 DTO
  -> JSON Schema 校验
  -> 业务规则校验
  -> 失败时进入 Repair
  -> Repair 失败则有限重试原 Agent
  -> 记录 parse / schema / repair / retry 指标
  -> 返回强类型 DTO
```

### MVP Schema

第一版只定义必要 Schema：

| Schema | 用途 |
| --- | --- |
| `title_options.schema.json` | 标题候选 |
| `outline_result.schema.json` | 大纲 |
| `image_plan.schema.json` | 配图计划 |
| `review_result.schema.json` | 通用评审结果 |
| `image_review_result.schema.json` | 图片结果评审 |

## 10. Memory 设计

系统中的 Memory 分为短期、中期、长期三层。

| 类型 | 范围 | 系统体现 | MVP 是否必须 |
| --- | --- | --- | --- |
| 短期记忆 | 当前一次 Agent 执行 | `ArticleContext`、`ImagePlan`、当前阶段输入输出、当前 review observation | 是 |
| 中期记忆 | 一篇文章生成生命周期 | 阶段结果、用户反馈、评审记录、重试记录、工具执行记录、版本记录 | 是 |
| 长期记忆 | 用户跨任务沉淀 | 写作 Skill、用户偏好、历史文章、个人知识库、风格样本 | 第二阶段 |

### 短期记忆

短期记忆存在于当前任务上下文中，用于让 Agent 在当前阶段知道前序状态。

示例：

- 原始主题。
- 已选标题。
- 已确认大纲。
- 当前正文草稿。
- 当前配图计划。
- ReviewAgent 的问题和建议。
- 图片工具返回的 observation。

### 中期记忆

中期记忆用于还原一篇文章的完整生成过程。

MVP 建议通过 `AgentLog.metadata` 或新增 `article_generation_trace` 表实现：

- 每个阶段的输入输出摘要。
- 用户每轮反馈。
- ReviewAgent 每轮评分。
- JSON 解析、修复、重试记录。
- 图片工具调用、失败原因、重规划记录。
- 最终采纳版本。

### 长期记忆

长期记忆不作为第一阶段必需项，避免 MVP 变重。第二阶段可以和 RAG/Skill 结合：

- 用户写作风格文档。
- 历史高质量文章。
- 账号人设和品牌设定。
- 常用标题风格。
- 禁用表达和偏好表达。
- 专业知识库。

## 11. RAG 可选增强

RAG 不作为默认流程，只在用户开启“知识库增强”或上传参考资料时启用。

### 设计动机

| 场景 | RAG 解决的问题 |
| --- | --- |
| 用户有个人写作风格 | 让输出贴近历史文章和表达习惯 |
| 用户有账号人设或世界观设定 | 保持跨文章设定一致 |
| 用户有专业资料 | 减少专业内容幻觉，提高事实密度 |
| 用户上传当前任务参考文档 | 让文章基于指定资料创作 |

### MVP 技术方案

| 层 | 技术 |
| --- | --- |
| 元数据 | MySQL |
| 原始文档 | COS 或本地文件系统 |
| 向量存储 | PostgreSQL + pgvector |
| 缓存和任务状态 | Redis |
| 文档解析 | TXT/Markdown 直接读取，PDFBox 解析 PDF，Apache POI 解析 DOCX |

### 第一阶段 RAG 流程

```text
用户上传文档
  -> 保存原始文件
  -> MySQL 保存文档元数据
  -> 异步解析文本
  -> 清洗和 chunking
  -> embedding
  -> 写入 PGVector

用户发起创作
  -> RetrievalDecisionService 判断是否检索
  -> Retriever 召回 topK chunks
  -> ContextBuilder 构建写作上下文
  -> ArticleAgent 使用上下文生成
```

第一阶段只使用规则服务 `RetrievalDecisionService`，不引入 `RetrievalDecisionAgent`。

## 12. MCP 可选增强

MCP 用于标准化工具接口，优先应用在图片工具层。

第一阶段不要求模型直接多轮调用 MCP 工具，可以先让 `ImageToolExecutor` 通过 `McpToolGateway` 调用标准化工具。

```text
ImageAgent 生成 image plan
  -> ImageToolExecutor
  -> McpToolGateway
  -> MCP Server
  -> 具体图片服务
```

### 推荐 MCP Tool

| Tool | 能力 |
| --- | --- |
| `search_stock_image` | 检索真实图片 |
| `generate_image` | AI 生图 |
| `render_mermaid` | 渲染 Mermaid |
| `search_icon` | 检索图标 |
| `generate_svg_diagram` | 生成 SVG 示意图 |
| `upload_asset` | 上传图片到对象存储 |

## 13. 数据和日志

### AgentLog 扩展

MVP 优先复用现有 `AgentLog`，通过 `metadata` 承载扩展字段，后续再拆表。

建议记录：

| 字段 | 说明 |
| --- | --- |
| `traceId` | 一次完整文章生成链路 ID |
| `phase` | TITLE / OUTLINE / CONTENT / IMAGE_PLAN / IMAGE_EXECUTE / REVIEW |
| `status` | RUNNING / SUCCESS / FAILED / RETRYING |
| `durationMs` | 阶段耗时 |
| `retryCount` | 当前阶段重试次数 |
| `repairCount` | JSON 修复次数 |
| `jsonParseSuccess` | JSON 解析是否成功 |
| `schemaValid` | Schema 是否通过 |
| `qualityScore` | ReviewAgent 评分 |
| `reviewApproved` | ReviewAgent 是否通过 |
| `toolName` | 工具名称 |
| `observation` | ReviewAgent 对工具结果的观察 |
| `fallbackUsed` | 是否触发 fallback |

### 生成过程回放

MVP 提供按 `taskId` 查询生成过程的接口，前端可先用列表或时间线展示：

- 阶段开始和结束时间。
- Agent 输入输出摘要。
- 评审分数和建议。
- 用户反馈。
- JSON 修复和重试记录。
- 图片工具调用结果。
- 图片重规划记录。

## 14. 部署视图

```text
Frontend
  -> Backend API
    -> ArticleAgent / ImageAgent / ReviewAgent
    -> JsonStructuredOutputService
    -> ImageToolExecutor
    -> AgentLogService
    -> SkillService
    -> MySQL
    -> Redis
    -> COS

Phase 2 optional:
    -> PostgreSQL + pgvector
    -> McpToolGateway
    -> MCP Servers
```

## 15. MVP 边界

第一版必须完成：

1. `ArticleAgent` 阶段化。
2. `ReviewAgent` 基础评审。
3. `JsonStructuredOutputService` 基础闭环。
4. `ImageAgent` 图片评审和重规划闭环。
5. `AgentLog` 和生成过程回放基础版。
6. 写作 Skill 配置化。

第一版暂不完成：

1. RAG 知识库。
2. MCP 标准化工具。
3. RetrievalDecisionAgent。
4. QueryRewriteAgent。
5. Reranker。
6. 大规模 AgentEval。
7. 复杂前端可视化分析面板。
