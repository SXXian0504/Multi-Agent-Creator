# Multi-Agent-Creator 改进方案与实施计划

## 1. 目标

当前项目已经具备标题生成、大纲生成、正文生成、配图分析、图片生成、图文合成、SSE 流式输出、AOP 执行日志等能力。后续优化目标是将现有固定流程升级为具备阶段状态、质量评审、人工确认、工具重规划、结构化输出保障和离线评测能力的多 Agent 图文创作系统。

本方案面向后续开发者，重点描述系统改造方向、模块边界、数据结构、执行流程和实施顺序。

## 2. 核心改造方向

1. 将文本创作能力聚合为 `ArticleAgent`，负责标题、大纲、正文的阶段化生成和用户反馈迭代。
2. 将配图规划能力升级为 `ImageAgent`，负责配图计划、工具选择和基于评审结果的重规划。
3. 引入 `ReviewAgent`，统一评审标题、大纲、正文、配图计划和图片工具执行结果。
4. 引入 `JsonStructuredOutputService`，统一处理结构化输出的解析、校验、修复、重试和指标记录。
5. 扩展 `AgentLog`，记录每个 Agent、每个阶段、每次工具调用、每次评审和每次重试。
6. 构建 `AgentEval` 离线评测脚本，用固定主题集对比优化前后的质量、稳定性和耗时。

## 3. 总体架构

| 模块 | 职责 | 类型 |
| --- | --- | --- |
| `ArticleAgent` | 标题、大纲、正文生成；接收用户反馈后重写当前阶段结果 | Agent |
| `ImageAgent` | 分析正文配图需求；选择图片工具；根据评审结果重规划 | Agent |
| `ReviewAgent` | 评审文本结果、配图计划、图片工具结果；输出评分、问题和修改建议 | Agent |
| `JsonStructuredOutputService` | JSON 提取、解析、Schema 校验、业务校验、修复、重试、指标记录 | 基础服务 |
| `ImageToolExecutor` | 执行 Pexels、AI 生图、Mermaid、Iconify、SVG 等工具 | 工具执行层 |
| `AgentLogService` | 持久化 Agent 执行过程、工具调用过程、评审过程 | 基础设施 |
| `AgentEval` | 离线运行测试集，生成评测报告 | 评测工具 |

## 4. 主流程设计

```text
用户输入主题
  -> ArticleAgent 生成标题候选
  -> JsonStructuredOutputService 校验标题结构
  -> ReviewAgent 评审标题候选
  -> 用户选择标题或追加修改要求
  -> ArticleAgent 生成大纲
  -> JsonStructuredOutputService 校验大纲结构
  -> ReviewAgent 评审大纲
  -> 用户确认大纲或追加修改要求
  -> ArticleAgent 生成正文
  -> ReviewAgent 评审正文
  -> 用户确认正文或追加修改要求
  -> ImageAgent 生成配图计划
  -> JsonStructuredOutputService 校验配图计划
  -> ImageToolExecutor 执行图片工具
  -> ReviewAgent 评审图片工具结果
  -> 不通过则返回 observation 给 ImageAgent 重规划
  -> 通过后图文合成
  -> 用户最终确认
  -> 持久化文章和生成过程日志
```

## 5. 阶段状态设计

建议在文章任务中显式维护阶段状态，便于 Human-in-the-loop、恢复执行、日志回放和前端展示。

| 状态 | 说明 |
| --- | --- |
| `TITLE_GENERATING` | 标题生成中 |
| `TITLE_REVIEWING` | 标题评审中 |
| `TITLE_WAITING_USER_CONFIRM` | 等待用户选择或反馈标题 |
| `OUTLINE_GENERATING` | 大纲生成中 |
| `OUTLINE_REVIEWING` | 大纲评审中 |
| `OUTLINE_WAITING_USER_CONFIRM` | 等待用户确认或修改大纲 |
| `CONTENT_GENERATING` | 正文生成中 |
| `CONTENT_REVIEWING` | 正文评审中 |
| `CONTENT_WAITING_USER_CONFIRM` | 等待用户确认或修改正文 |
| `IMAGE_PLANNING` | 配图计划生成中 |
| `IMAGE_EXECUTING` | 图片工具执行中 |
| `IMAGE_REVIEWING` | 图片结果评审中 |
| `IMAGE_REPLANNING` | 图片结果未通过，重新规划中 |
| `MERGING` | 图文合成中 |
| `COMPLETED` | 全流程完成 |
| `FAILED` | 任务失败 |

## 6. ArticleAgent 设计

### 6.1 职责

`ArticleAgent` 负责所有文本创作阶段：

- 根据主题生成标题候选。
- 根据用户选择的标题生成大纲。
- 根据确认后的大纲生成正文。
- 根据用户补充要求重新生成标题、大纲或正文。
- 保持标题、大纲、正文之间的上下文一致性。

### 6.2 方法粒度

建议保留一个 `ArticleAgent` 类，但内部使用阶段方法区分执行边界：

```java
@AgentExecution(value = "article_generate_titles", phase = "TITLE")
List<TitleOption> generateTitles(ArticleContext context);

@AgentExecution(value = "article_generate_outline", phase = "OUTLINE")
OutlineResult generateOutline(ArticleContext context);

@AgentExecution(value = "article_generate_content", phase = "CONTENT")
String generateContent(ArticleContext context);
```

这样可以保留统一的文本创作上下文，同时让 AOP 继续记录标题、大纲、正文各自的耗时、异常、成功率和重试次数。

### 6.3 用户反馈接入

用户在标题、大纲、正文任一阶段不满意时，系统保存反馈并重新生成当前阶段结果。

建议维护 `UserFeedback`：

```json
{
  "taskId": "xxx",
  "phase": "OUTLINE",
  "feedback": "增加一个工程实践案例章节",
  "createdAt": "2026-05-15T12:00:00"
}
```

重新生成时，`ArticleAgent` 接收当前上下文和历史反馈：

```text
原始主题
已确认标题
当前大纲或正文
用户本轮反馈
历史反馈摘要
当前 Skill 配置
可选 RAG 上下文
```

## 7. 正文校验设计

正文最终交付形态是 Markdown，不强制要求使用 JSON Schema。正文阶段主要做格式校验、结构一致性校验和质量评审。

| 校验类型 | 校验内容 |
| --- | --- |
| 格式校验 | 是否为空、是否为 Markdown、是否包含章节标题、字数是否达标 |
| 结构校验 | 是否覆盖大纲章节、章节顺序是否一致、是否偏离标题主题 |
| 质量校验 | 可读性、逻辑连贯性、信息密度、风格一致性 |
| 安全校验 | 是否包含敏感内容、广告、明显幻觉或不合规内容 |

如后续需要更强的结构化评估，可以让正文生成返回包装结构：

```json
{
  "markdown": "## 第一节...",
  "sectionCoverage": [
    {
      "section": 1,
      "covered": true
    }
  ],
  "summary": "本文主要讨论..."
}
```

第一版建议正文仍输出 Markdown，避免长正文 JSON 转义和流式输出复杂度过高。

## 8. ImageAgent 设计

### 8.1 职责

`ImageAgent` 负责配图规划和重规划：

- 根据最终确认的标题、大纲、正文分析配图需求。
- 决定每个配图位置使用哪类工具。
- 生成工具参数，例如关键词、AI 生图 prompt、Mermaid 代码、SVG 需求。
- 接收 `ReviewAgent` 对图片结果的 observation，重新选择工具或改写参数。
- 控制重试次数，超过上限后触发 fallback 或交给用户确认。

### 8.2 配图计划结构

建议 ImageAgent 输出 `ImagePlan`：

```json
{
  "imageRequirements": [
    {
      "position": 1,
      "type": "cover",
      "sectionTitle": "",
      "imageSource": "NANO_BANANA",
      "keywords": "",
      "prompt": "A modern minimalist illustration...",
      "placeholderId": "",
      "reason": "封面需要表达抽象技术主题，真实图库难以准确匹配",
      "retryCount": 0
    },
    {
      "position": 2,
      "type": "section",
      "sectionTitle": "系统架构设计方法",
      "imageSource": "MERMAID",
      "keywords": "",
      "prompt": "flowchart TD\n  A[需求分析] --> B[模块划分]",
      "placeholderId": "{{IMAGE_PLACEHOLDER_1}}",
      "reason": "该章节描述流程关系，使用 Mermaid 更容易表达结构",
      "retryCount": 0
    }
  ]
}
```

### 8.3 工具选择规则

第一版可以使用规则 + LLM 共同决策。

| 内容类型 | 推荐工具 |
| --- | --- |
| 技术流程、架构关系、时序关系 | `MERMAID` / `SVG_DIAGRAM` |
| 封面、抽象概念、创意表达 | `NANO_BANANA` / `QWEN_IMAGE` |
| 真实场景、人物、办公、自然风景 | `PEXELS` |
| 小图标、装饰符号、列表视觉元素 | `ICONIFY` |
| 轻松幽默风格的表情表达 | `EMOJI_PACK` |
| 工具不可用或多次失败 | `PICSUM` fallback |

### 8.4 配图闭环

配图阶段采用“规划 -> 执行 -> 评审 -> 重规划”的闭环。

```text
ImageAgent 生成配图计划
  -> ImageToolExecutor 执行图片工具
  -> ReviewAgent 评审图片结果
  -> 不通过：生成 observation + revision advice
  -> ImageAgent 根据 observation 重新规划
  -> 最多重试 N 次
  -> 仍失败：fallback 或交给用户确认
```

这个闭环中，各模块职责如下：

| 模块 | 职责 |
| --- | --- |
| `ImageAgent` | 规划和重规划：选择工具、生成参数、改写参数 |
| `ImageToolExecutor` | 执行工具：调用 Pexels、Mermaid、AI 生图、Iconify 等 |
| `ReviewAgent` | 评估结果：判断图片是否相关、是否可用、是否需要重试 |
| 后端策略 | 控制最大重试次数、超时、fallback、成本限制 |

### 8.5 Observation 结构

当图片结果不通过时，`ReviewAgent` 输出结构化 observation，供 `ImageAgent` 重新规划。

```json
{
  "approved": false,
  "score": 62,
  "position": 2,
  "toolUsed": "PEXELS",
  "failureType": "LOW_RELEVANCE",
  "observation": "返回图片是普通办公场景，无法表达系统架构流程关系",
  "suggestedAction": {
    "type": "CHANGE_TOOL",
    "nextTool": "MERMAID",
    "newPrompt": "flowchart TD\n  A[需求分析] --> B[模块划分] --> C[接口设计]",
    "reason": "该段落需要表达流程关系，图表工具比真实图片更合适"
  }
}
```

建议定义 `failureType` 枚举：

| failureType | 说明 | 处理建议 |
| --- | --- | --- |
| `TOOL_UNAVAILABLE` | 工具不可用 | 切换工具或 fallback |
| `RENDER_FAILED` | Mermaid/SVG 渲染失败 | 修复 prompt 或切换工具 |
| `LOW_RELEVANCE` | 图片与段落相关性低 | 改关键词或换工具 |
| `LOW_VISUAL_QUALITY` | 图片质量差 | 改 prompt 或换工具 |
| `PLACEHOLDER_MISMATCH` | 占位符无法匹配正文 | 修正 placeholder |
| `UPLOAD_FAILED` | 上传对象存储失败 | 重试上传或使用原始 URL |
| `COST_LIMIT_EXCEEDED` | 成本超过限制 | 切换低成本工具 |

### 8.6 重试策略

建议使用分层重试：

| 场景 | 策略 |
| --- | --- |
| 工具短暂失败 | 同工具重试 1 次 |
| 相关性不足 | 交给 ImageAgent 改写关键词或 prompt |
| 工具类型不合适 | ImageAgent 切换工具 |
| 渲染语法错误 | 让 ImageAgent 修复 Mermaid/SVG prompt |
| 达到最大重试次数 | fallback 或标记为待用户确认 |

建议默认最大重试次数：

```text
每张图片最多重规划 2 次
每个工具调用最多执行 2 次
整个图片阶段最多 6 次工具调用失败
```

## 9. ImageToolExecutor 设计

### 9.1 职责

`ImageToolExecutor` 负责执行实际图片工具调用，不负责判断图片是否“好”。它只返回工具执行结果和基础状态。

输入：

```json
{
  "imageSource": "MERMAID",
  "keywords": "",
  "prompt": "flowchart TD...",
  "position": 2,
  "type": "section",
  "sectionTitle": "系统架构设计方法",
  "placeholderId": "{{IMAGE_PLACEHOLDER_1}}"
}
```

输出：

```json
{
  "position": 2,
  "success": true,
  "method": "MERMAID",
  "url": "https://cdn.xxx/mermaid/xxx.svg",
  "keywords": "",
  "prompt": "flowchart TD...",
  "sectionTitle": "系统架构设计方法",
  "placeholderId": "{{IMAGE_PLACEHOLDER_1}}",
  "error": null,
  "durationMs": 1200,
  "fallbackUsed": false
}
```

### 9.2 与现有代码关系

当前项目已有：

- `ImageGenerationTool`
- `ImageServiceStrategy`
- `ImageSearchService`
- `ParallelImageGenerator`

第一阶段可以保留现有实现，只做抽象命名和日志增强：

```text
ParallelImageGenerator
  -> ImageGenerationTool.generateImageDirect()
  -> ImageServiceStrategy.getImageAndUpload()
  -> ImageSearchService 实现
```

后续可将 `ParallelImageGenerator` 逐步升级为 `ImageToolExecutor`，并保留并行执行能力。

## 10. ReviewAgent 设计

### 10.1 职责

`ReviewAgent` 负责所有质量评审：

- 标题评审。
- 大纲评审。
- 正文评审。
- 配图计划评审。
- 图片工具执行结果评审。

### 10.2 通用评审输出

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
    "大纲第三节和第四节边界重叠"
  ],
  "suggestions": [
    "第三节聚焦方法，第四节聚焦案例"
  ],
  "nextAction": "REVISE"
}
```

### 10.3 标题评审 Rubric

| 维度 | 分值 | 说明 |
| --- | --- | --- |
| 主题相关性 | 25 | 是否准确对应用户主题 |
| 吸引力 | 25 | 是否有点击欲望，收益点是否明确 |
| 清晰度 | 20 | 是否容易理解 |
| 差异性 | 15 | 多个候选标题是否角度不同 |
| 风格匹配 | 15 | 是否符合当前写作 Skill |

### 10.4 大纲评审 Rubric

| 维度 | 分值 | 说明 |
| --- | --- | --- |
| 结构完整性 | 25 | 是否有引入、展开、总结 |
| 逻辑连贯性 | 25 | 章节顺序是否自然 |
| 信息密度 | 20 | 是否有足够观点和细节 |
| 用户要求覆盖 | 20 | 是否体现用户补充要求 |
| 可写作性 | 10 | 是否适合展开成正文 |

### 10.5 正文评审 Rubric

| 维度 | 分值 | 说明 |
| --- | --- | --- |
| 主题一致性 | 20 | 是否围绕标题和大纲 |
| 内容完整度 | 20 | 是否覆盖大纲要点 |
| 可读性 | 20 | 段落、语言、过渡是否自然 |
| 事实与案例 | 15 | 是否有依据、案例或具体信息 |
| 风格一致性 | 15 | 是否符合当前写作 Skill |
| 原创表达 | 10 | 是否避免模板化、空泛表达 |

### 10.6 图片结果评审 Rubric

| 维度 | 分值 | 说明 |
| --- | --- | --- |
| 可用性 | 20 | URL 是否有效，是否上传成功，是否可展示 |
| 相关性 | 30 | 图片是否贴合对应章节内容 |
| 工具选择合理性 | 20 | 工具是否适合表达该内容 |
| 视觉质量 | 15 | 图片清晰度、构图、观感是否可接受 |
| 占位符匹配 | 10 | placeholder 是否能正确替换正文位置 |
| 成本与稳定性 | 5 | 是否避免不必要的高成本工具 |

### 10.7 通过阈值

| 分数 | 策略 |
| --- | --- |
| 90-100 | 直接通过 |
| 80-89 | 通过，记录轻微优化建议 |
| 70-79 | 不通过，进入一次重写或重规划 |
| 70 以下 | 明显失败，优先重写、换工具或 fallback |

第一版建议通过阈值设为 80 分。

## 11. JsonStructuredOutputService 设计

### 11.1 当前问题

当前项目主要依赖 Prompt 中的 JSON 示例和 Gson 解析。该方式对以下情况稳定性不足：

- 模型输出解释文本。
- 模型输出 Markdown 代码块。
- 字段缺失。
- 枚举值错误。
- 数组数量不符合要求。
- 占位符和正文不匹配。

### 11.2 服务职责

`JsonStructuredOutputService` 统一处理所有结构化输出。

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

### 11.3 第一批 Schema

建议先定义：

- `title_options.schema.json`
- `outline_result.schema.json`
- `image_plan.schema.json`
- `review_result.schema.json`
- `image_review_result.schema.json`

### 11.4 业务校验规则

| 对象 | 校验规则 |
| --- | --- |
| 标题候选 | 数量 3-5 个；主标题和副标题非空；长度限制 |
| 大纲 | sections 非空；section 编号连续；points 非空 |
| 配图计划 | imageSource 必须属于枚举；position 不重复；placeholder 格式合法 |
| 图片结果 | URL 非空；placeholder 能在正文中找到；method 与计划一致或明确 fallback |
| Review 结果 | score 在 0-100；approved 与 score 阈值一致；nextAction 合法 |

## 12. Skill 设计

项目已有写作风格能力，后续可将其沉淀为可配置 Skill。

Skill 用于描述不同写作任务的稳定配置：

- 文章结构模板。
- 风格 Prompt。
- few-shot 示例。
- 标题生成策略。
- 正文风格规则。
- 配图偏好。
- ReviewAgent Rubric 权重。
- 输出 Schema。

建议第一批 Skill：

| Skill | 场景 |
| --- | --- |
| `tech_article` | 技术文章、架构说明、工程实践 |
| `wechat_article` | 公众号长文 |
| `marketing_copy` | 产品介绍、营销转化 |
| `educational_article` | 教育科普 |
| `emotional_article` | 情感类内容 |

`ArticleAgent` 和 `ReviewAgent` 都应读取当前 Skill 配置。`ImageAgent` 可读取 Skill 中的配图偏好。

## 13. RAG 设计

RAG 不作为默认主链路必经步骤，而是作为可选的个人知识库增强能力。用户可以上传自己的历史文章、写作风格说明、人物设定、品牌资料、专业文档等内容，构建专属知识库。图文创作时，用户可以通过开关允许系统使用知识库；系统再根据规则或 Agent 决策判断是否真正检索。

### 13.1 设计原则

- 知识库增强默认关闭，由用户显式开启。
- 开启知识库增强表示“允许检索”，不表示每次强制检索。
- 第一阶段使用确定性的 `RetrievalDecisionService`，优先保证稳定、低成本、可解释。
- 第二阶段再引入 `RetrievalDecisionAgent`，处理模糊需求、知识库选择和查询生成。
- RAG 的目标是解决个性化风格、私有设定、专业知识和资料复用问题，不作为技术堆砌项。

### 13.2 知识库类型

建议逻辑上将用户知识库分为以下类型：

| 类型 | 内容 | 用途 |
| --- | --- | --- |
| `style_memory` | 用户历史文章、标题习惯、常用表达、禁用表达 | 保持用户个人写作风格 |
| `persona_memory` | 账号人设、品牌调性、人物设定、世界观设定 | 保持设定一致 |
| `domain_knowledge` | 技术文档、行业资料、课程资料、报告 | 补充专业知识、事实和案例 |
| `project_docs` | 产品文档、FAQ、业务说明、内部资料 | 支持企业或项目内容创作 |
| `task_reference` | 用户针对当前任务临时上传的参考资料 | 基于资料改写、总结、扩写 |

### 13.3 存储方案

推荐采用三层存储：

```text
MySQL：知识库、文档、索引任务等业务元数据
COS / 本地文件系统：用户上传的原始文件
PGVector：chunk 文本、embedding 向量、检索 metadata
```

Redis 可用于异步任务状态、短期检索缓存和限流，不作为知识库主存储。

### 13.4 MySQL 元数据表

建议新增：

```text
knowledge_base
- id
- userId
- name
- description
- type
- status
- createTime
- updateTime

knowledge_document
- id
- userId
- knowledgeBaseId
- fileName
- fileType
- fileSize
- storageUrl
- parseStatus
- chunkCount
- errorMessage
- createTime
- updateTime

knowledge_ingestion_job
- id
- documentId
- status
- startedAt
- finishedAt
- errorMessage
```

### 13.5 PGVector Chunk 表

建议在 PostgreSQL + pgvector 中保存 chunk 和 embedding：

```sql
CREATE TABLE knowledge_chunk (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  content TEXT NOT NULL,
  content_hash VARCHAR(64),
  token_count INT,
  metadata JSONB,
  embedding VECTOR(1536),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

检索时必须按 `user_id` 和可访问的 `knowledge_base_id` 过滤，避免不同用户之间的数据串用。

### 13.6 文档索引流程

```text
用户上传文档
  -> 原始文件保存到 COS 或本地文件系统
  -> MySQL 记录 document 元信息
  -> 异步解析文档
  -> 文本清洗
  -> chunking
  -> 调用 embedding 模型生成向量
  -> 写入 PGVector
  -> 更新 document.parseStatus 和 chunkCount
```

第一阶段建议支持：

| 文件类型 | 解析方案 |
| --- | --- |
| `.txt` / `.md` | 直接读取文本 |
| `.pdf` | PDFBox |
| `.docx` | Apache POI |

后续可扩展 `.html`、`.csv`、网页 URL 导入等。

### 13.7 Chunking 策略

第一阶段使用简单、可控的规则切分：

- 优先按 Markdown 标题、自然段、列表项切分。
- 单个 chunk 控制在 500-800 中文字。
- overlap 控制在 80-150 字。
- metadata 中保存标题路径、文件名、知识库类型、chunkIndex。

示例 metadata：

```json
{
  "fileName": "我的技术文章风格.md",
  "knowledgeType": "style_memory",
  "headingPath": "写作风格 > 标题习惯",
  "tags": ["技术文章", "公众号"],
  "source": "user_upload"
}
```

### 13.8 第一阶段：RetrievalDecisionService

第一阶段使用普通服务做检索决策，遵循最小 MVP。

职责：

- 判断用户是否开启知识库增强。
- 判断用户是否有可用知识库。
- 根据硬规则决定跳过检索、强制检索或进入后续扩展判断。
- 生成基础检索查询。
- 控制权限、成本和最大召回数量。

输入：

```json
{
  "knowledgeEnhanced": true,
  "topic": "程序员如何提升系统设计能力",
  "style": "TECH",
  "userDescription": "需要结合我的过往文章风格，并加入工程案例",
  "availableKnowledgeBases": [
    {
      "id": 1,
      "type": "style_memory",
      "name": "我的公众号历史文章"
    },
    {
      "id": 2,
      "type": "domain_knowledge",
      "name": "系统设计资料库"
    }
  ]
}
```

输出：

```json
{
  "shouldRetrieve": true,
  "decisionType": "RULE_BASED",
  "reason": "用户开启知识库增强，并要求结合历史写作风格和工程案例",
  "targetKnowledgeBaseIds": [1, 2],
  "queries": [
    "程序员如何提升系统设计能力 工程案例",
    "系统设计能力提升 实践路径",
    "历史文章 技术成长 标题风格"
  ],
  "topK": 8
}
```

第一阶段触发检索规则：

- 用户未开启知识库增强：跳过。
- 用户没有可用知识库：跳过。
- 用户上传了当前任务参考资料：检索 `task_reference`。
- 用户选择“使用我的写作风格”：检索 `style_memory`。
- 用户描述中包含“参考我的资料、按照我的设定、结合文档、基于知识库、专业、案例、数据、报告”等关键词：检索对应知识库。
- 文章类型为技术、教育、产品文档、行业分析时，如果存在 `domain_knowledge`，允许检索。

第一阶段检索流程：

```text
用户请求
  -> RetrievalDecisionService
  -> shouldRetrieve=false：跳过 RAG
  -> shouldRetrieve=true：生成基础 queries
  -> Retriever 从 PGVector topK 召回
  -> ContextBuilder 压缩为写作上下文
  -> ArticleAgent 使用上下文生成
  -> ReviewAgent 检查是否偏离用户资料或设定
```

第一阶段暂不做：

- LLM 决策是否检索。
- 多轮查询规划。
- 复杂 rerank。
- 自动从互联网抓取资料。
- 跨用户知识共享。

### 13.9 第二阶段：RetrievalDecisionAgent

第二阶段在第一阶段稳定后，引入 `RetrievalDecisionAgent` 处理模糊场景。

触发条件：

- 规则无法明确判断是否检索。
- 用户需求较复杂，需要判断检索哪些知识库。
- 需要生成更高质量的多查询。
- 需要按阶段选择知识库，例如标题阶段检索风格，正文阶段检索案例。

推荐混合流程：

```text
RetrievalDecisionService
  -> HARD_SKIP：直接跳过
  -> HARD_RETRIEVE：直接检索
  -> NEED_AGENT_DECISION：调用 RetrievalDecisionAgent
  -> JsonStructuredOutputService 校验 Agent 决策
  -> 校验失败：回退到规则决策
```

`RetrievalDecisionAgent` 输出：

```json
{
  "shouldRetrieve": true,
  "reason": "用户要求结合个人写作风格，且主题涉及专业知识",
  "targetKnowledgeBaseIds": [1, 2],
  "queries": [
    "用户历史文章 技术成长 标题风格",
    "系统设计 能力模型 工程实践",
    "架构设计 实践路径 程序员成长"
  ],
  "stageUsage": {
    "TITLE": ["style_memory"],
    "OUTLINE": ["domain_knowledge"],
    "CONTENT": ["style_memory", "domain_knowledge"]
  }
}
```

第二阶段可增加：

- QueryRewriteAgent。
- Reranker。
- 多知识库路由。
- 阶段级检索策略。
- ReviewAgent 对资料覆盖度的评分。

### 13.10 ContextBuilder

`ContextBuilder` 将召回 chunk 压缩为可注入 Agent 的上下文。

建议输出：

```json
{
  "styleReferences": [
    "用户常用标题结构：数字 + 痛点 + 结果。",
    "用户正文偏好：短段落，先结论后解释。"
  ],
  "personaFacts": [
    "账号人设：面向 3-5 年经验程序员。"
  ],
  "domainFacts": [
    "系统设计能力包含需求分析、模块划分、接口设计、性能权衡。"
  ],
  "citations": [
    {
      "documentId": 12,
      "chunkId": 128,
      "source": "系统设计资料库.pdf"
    }
  ]
}
```

第一阶段可以先使用 topK chunk 拼接 + 简单摘要；第二阶段再加入 LLM 摘要压缩。

## 14. MCP 接入设计

MCP 用于将图片工具标准化为外部工具服务。第一版不要求模型直接通过 MCP 多轮调用工具，可以先将现有图片服务包装成标准工具接口。

### 14.1 接入目标

- 降低 `ImageAgent` 与具体图片服务的耦合。
- 统一工具输入输出结构。
- 便于后续新增视频、图表、截图、素材库等工具。
- 支持多个 Agent 复用同一套工具能力。

### 14.2 推荐 MCP 工具

| MCP Tool | 能力 |
| --- | --- |
| `search_stock_image` | 根据关键词检索真实图片 |
| `generate_image` | 根据 prompt 生成 AI 图片 |
| `render_mermaid` | 将 Mermaid 代码渲染为图片 |
| `search_icon` | 从 Iconify 检索图标 |
| `generate_svg_diagram` | 根据概念生成 SVG 示意图 |
| `upload_asset` | 上传图片到对象存储 |

### 14.3 分阶段接入

第一阶段：工具层 MCP 化。

```text
ImageAgent 生成 image plan
  -> ImageToolExecutor
  -> MCP ToolGateway
  -> MCP Server
  -> 具体图片服务
```

第二阶段：支持 Agent 根据 observation 多轮调用 MCP 工具。

```text
ImageAgent
  -> 选择 MCP tool
  -> 获取 tool result
  -> ReviewAgent 输出 observation
  -> ImageAgent 再选择 tool 或改写参数
```

## 15. AgentLog 扩展

当前 `AgentLog` 已有基础执行字段。建议扩展以下字段。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `traceId` | varchar | 一次完整文章生成链路 ID |
| `parentLogId` | bigint | 父级日志 ID |
| `agentName` | varchar | ArticleAgent / ImageAgent / ReviewAgent |
| `phase` | varchar | TITLE / OUTLINE / CONTENT / IMAGE_PLAN / IMAGE_EXECUTE / REVIEW |
| `status` | varchar | RUNNING / SUCCESS / FAILED / RETRYING |
| `durationMs` | int | 耗时 |
| `modelName` | varchar | 使用模型 |
| `inputTokens` | int | 输入 token |
| `outputTokens` | int | 输出 token |
| `retryCount` | int | 当前阶段重试次数 |
| `repairCount` | int | JSON 修复次数 |
| `jsonParseSuccess` | boolean | JSON 解析是否成功 |
| `schemaValid` | boolean | Schema 是否通过 |
| `qualityScore` | int | ReviewAgent 评分 |
| `reviewApproved` | boolean | ReviewAgent 是否通过 |
| `nextAction` | varchar | CONTINUE / REVISE / RETRY / FALLBACK / STOP |
| `toolName` | varchar | 工具名称 |
| `toolInput` | text/json | 工具输入摘要 |
| `toolOutput` | text/json | 工具输出摘要 |
| `observation` | text/json | ReviewAgent 对工具结果的观察 |
| `fallbackUsed` | boolean | 是否触发 fallback |
| `metadata` | json | 其他扩展信息 |

如果短期不方便频繁改表，可以先新增 `metadata` 字段，把扩展信息以 JSON 形式存储。

## 16. 生成过程回放

建议新增按 `taskId` 查询生成过程的接口和前端视图。

展示内容：

- 每个阶段的开始、结束和耗时。
- 每个 Agent 的输入摘要和输出摘要。
- 每次 ReviewAgent 的评分、问题、建议和 nextAction。
- 用户每次反馈内容。
- JSON 解析、修复、重试记录。
- 图片工具选择理由。
- 图片工具执行结果。
- 图片结果不通过时的 observation 和重规划记录。
- fallback 记录。

## 17. AgentEval 离线评测

### 17.1 评测目标

用固定主题集对比优化前后的稳定性、生成质量和耗时。

建议比较：

| 方案 | 说明 |
| --- | --- |
| Baseline A | 单 Prompt 一次性生成整篇文章 |
| Baseline B | 当前固定流程版本 |
| Experiment C | ArticleAgent + ImageAgent + ReviewAgent + 结构化输出服务 |

### 17.2 测试集

准备 50-100 个主题，覆盖：

- 技术文章
- 职场成长
- 教育科普
- 情感文章
- 产品介绍
- 架构设计说明
- 案例分析

样例：

```json
{
  "topic": "程序员如何提升系统设计能力",
  "style": "TECH",
  "userDescription": "需要包含工程案例和实践路径",
  "expectedCriteria": ["结构清晰", "有案例", "适合公众号阅读"]
}
```

### 17.3 指标

系统稳定性：

- JSON 首次解析成功率。
- Schema 校验通过率。
- 平均重试次数。
- Agent 阶段成功率。
- 平均耗时。
- P95 耗时。
- fallback 触发率。

内容质量：

- 标题评分。
- 大纲评分。
- 正文评分。
- 配图计划评分。
- 图片结果评分。
- ReviewAgent 平均分。

用户交互：

- 标题采纳率。
- 大纲修改率。
- 正文重写率。
- 平均确认轮次。

图片工具：

- 每种工具调用次数。
- 每种工具成功率。
- 每种工具平均耗时。
- 图片评审通过率。
- 图片重规划次数。

### 17.4 报告输出

输出 Markdown 报告：

```text
eval_reports/
  2026-xx-xx-agent-eval.md
```

报告内容：

- 总体结果。
- 方案指标对比。
- Agent 成功率和耗时。
- JSON 失败案例。
- ReviewAgent 拦截案例。
- 图片工具选择分布。
- 图片重规划案例。
- 后续优化建议。

## 18. 实施顺序

### 阶段一：日志和阶段状态

目标：先建立可观测执行链路。

任务：

1. 增加或完善文章阶段状态。
2. 整理 `ArticleAgent` 的标题、大纲、正文阶段方法。
3. 扩展 `@AgentExecution` 或 AOP 记录 `phase`、`traceId`、`retryCount`。
4. 扩展 `AgentLog` 或新增 `metadata` 字段。
5. 提供按 `taskId` 查询生成过程日志的接口。

### 阶段二：ReviewAgent

目标：建立质量评审闭环。

任务：

1. 定义 `ReviewResult` DTO。
2. 实现标题、大纲、正文评审。
3. 实现配图计划和图片结果评审。
4. 设置 80 分通过阈值。
5. 将 ReviewAgent 评分和建议写入 AgentLog。

### 阶段三：JsonStructuredOutputService

目标：提高结构化输出稳定性。

任务：

1. 定义标题、大纲、配图计划、评审结果的 JSON Schema。
2. 封装 JSON 提取、解析、校验方法。
3. 加入业务规则校验。
4. 实现 JSON 修复和有限重试。
5. 记录 parse、schema、repair、retry 指标。

### 阶段四：ImageAgent 闭环升级

目标：实现配图规划、工具执行、评审、重规划闭环。

任务：

1. ImageAgent 输出带 reason 的 `ImagePlan`。
2. 保留并增强并行图片工具执行。
3. ReviewAgent 评审每张图片结果。
4. 不通过时输出 observation。
5. ImageAgent 根据 observation 重规划。
6. 增加最大重试次数和 fallback 策略。
7. 记录工具调用、评审、重规划过程。

### 阶段五：Skill 配置化

目标：将写作风格能力从 Prompt 常量升级为可配置 Skill。

任务：

1. 定义 Skill 配置结构。
2. 将现有技术、教育、情感、幽默等风格迁移为 Skill。
3. 让 ArticleAgent、ReviewAgent、ImageAgent 读取 Skill 配置。
4. 支持后续新增文章类型 Skill。

### 阶段六：MCP 工具标准化

目标：标准化图片工具接口。

任务：

1. 设计 MCP ToolGateway。
2. 将 Pexels、Mermaid、Iconify、AI 生图、SVG 生成封装为 MCP 工具。
3. ImageToolExecutor 通过 MCP ToolGateway 调用工具。
4. 保留原 Java Service 作为 fallback。

### 阶段七：RAG 第一阶段 MVP

目标：实现可选的个人知识库增强能力，先用规则服务完成稳定、低成本的最小闭环。

任务：

1. 支持用户创建知识库，区分 `style_memory`、`persona_memory`、`domain_knowledge`、`project_docs`、`task_reference`。
2. 支持用户上传 `.txt`、`.md`、`.pdf`、`.docx` 文档。
3. 原始文件保存到 COS 或本地文件系统，MySQL 保存知识库和文档元数据。
4. 实现异步文档解析、清洗、chunking、embedding 和 PGVector 写入。
5. 实现 `RetrievalDecisionService`，根据用户开关、知识库可用性、用户需求关键词和文章类型做规则判断。
6. 实现基础 Retriever，从 PGVector 按 `userId`、`knowledgeBaseId` 过滤召回 topK chunks。
7. 实现第一版 `ContextBuilder`，将召回 chunk 拼接或简单摘要为写作上下文。
8. ArticleAgent 在知识库增强开启且决策通过时使用检索上下文生成。
9. ReviewAgent 检查内容是否偏离用户资料、设定或风格要求。

暂不实现：

1. RetrievalDecisionAgent。
2. QueryRewriteAgent。
3. 复杂 Reranker。
4. 联网检索。
5. 跨用户知识共享。

### 阶段八：RAG 第二阶段 Agent 决策增强

目标：在第一阶段稳定后，引入 Agent 决策能力，处理模糊需求和多知识库路由。

任务：

1. 实现 `RetrievalDecisionAgent`，仅在 `RetrievalDecisionService` 返回 `NEED_AGENT_DECISION` 时调用。
2. 使用 `JsonStructuredOutputService` 校验 RetrievalDecisionAgent 输出。
3. 支持 Agent 选择目标知识库、生成多条查询、说明检索原因。
4. 支持阶段级检索策略，例如标题阶段检索风格，正文阶段检索案例。
5. 增加 QueryRewriteAgent 或 QueryRewriteService。
6. 增加 Reranker，提高召回结果质量。
7. ReviewAgent 增加资料覆盖度评分。

### 阶段九：AgentEval

目标：形成量化评估能力。

任务：

1. 准备 50-100 个测试主题。
2. 实现 Baseline A、Baseline B、Experiment C。
3. 自动收集稳定性、质量、耗时、工具调用指标。
4. 输出 Markdown 评测报告。

## 19. 优先级建议

第一优先级：

- 阶段状态。
- AgentLog 扩展。
- ReviewAgent。
- JsonStructuredOutputService。
- ImageAgent 配图评审和重规划闭环。

第二优先级：

- Skill 配置化。
- 生成过程回放页面。
- AgentEval。

第三优先级：

- MCP 工具标准化。
- RAG 第一阶段 MVP。
- RAG 第二阶段 Agent 决策增强。

不建议一开始同时引入 MCP、RAG、Skill、ReviewAgent、AgentEval。第一版应优先完成“生成 -> 校验 -> 评审 -> 用户确认 -> 重规划”的闭环，再逐步增加工具标准化和知识增强能力。
