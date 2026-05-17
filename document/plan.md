# Multi-Agent-Creator MVP 实施计划

## 1. 计划原则

本计划按照最小 MVP 原则拆分。每个阶段都必须能独立交付、独立验证，并且尽量复用当前项目已有能力。

优先级顺序：

1. 先完成 Agent 闭环：生成、校验、评审、用户确认、重试。
2. 再完成可观测：日志、过程回放、关键指标。
3. 再完成工具增强：图片重规划、Skill 配置化。
4. 最后再接入 MCP、RAG、离线评测。

## 2. 阶段总览

| 阶段 | 名称 | 目标 | MVP 必须 |
| --- | --- | --- | --- |
| 阶段 0 | 基线梳理 | 固化当前流程和数据结构，避免改造失控 | 是 |
| 阶段 1 | 阶段状态和日志 | 建立可观测、可恢复的执行链路 | 是 |
| 阶段 2 | ArticleAgent 阶段化 | 统一文本 Agent，并保留标题/大纲/正文边界 | 是 |
| 阶段 3 | JsonStructuredOutputService | 提高 JSON 输出稳定性 | 是 |
| 阶段 4 | ReviewAgent | 建立质量评审闭环 | 是 |
| 阶段 5 | ImageAgent 重规划闭环 | 让图片工具具备 observation -> replan 能力 | 是 |
| 阶段 6 | Skill 配置化 | 将写作风格沉淀为可配置能力 | 是 |
| 阶段 7 | 过程回放 | 让用户和开发者能查看生成全过程 | 建议是 |
| 阶段 8 | RAG MVP | 可选个人知识库增强 | 否 |
| 阶段 9 | MCP 工具标准化 | 标准化图片工具接口 | 否 |
| 阶段 10 | AgentEval | 形成量化评测报告 | 否 |

## 3. 阶段 0：基线梳理

### 目标

在正式改造前，明确当前系统的输入、输出、流程和可复用代码。

### 具体功能

1. 梳理当前生成链路：
   - 标题生成。
   - 大纲生成。
   - 正文生成。
   - 配图分析。
   - 图片生成。
   - 图文合成。
2. 梳理当前 DTO：
   - 标题结果。
   - 大纲结果。
   - 配图需求。
   - 图片生成结果。
3. 梳理当前日志：
   - AOP 记录了哪些字段。
   - `AgentLog` 已有哪些字段。
   - 哪些阶段缺少日志。
4. 梳理当前工具调用：
   - `ImageGenerationTool`。
   - `ImageServiceStrategy`。
   - 各图片服务实现。

### 流程

```text
阅读现有代码
  -> 画出当前流程
  -> 标注可复用模块
  -> 标注必须改造模块
  -> 输出基线说明
```

### 交付物

1. 当前流程说明。
2. 当前 DTO 清单。
3. 当前 Agent/Service 复用清单。
4. 当前问题清单。

### 检验标准

1. 能清楚说明当前项目是如何从主题生成完整图文的。
2. 能清楚说明哪些能力已经存在，哪些只是计划。
3. 不改业务逻辑，不引入新依赖。

## 4. 阶段 1：阶段状态和日志

### 目标

建立任务级执行状态和统一日志，为后续 Review、重试、回放打基础。

### 具体功能

1. 为文章任务增加阶段状态：
   - `TITLE_GENERATING`
   - `TITLE_REVIEWING`
   - `TITLE_WAITING_USER_CONFIRM`
   - `OUTLINE_GENERATING`
   - `OUTLINE_REVIEWING`
   - `OUTLINE_WAITING_USER_CONFIRM`
   - `CONTENT_GENERATING`
   - `CONTENT_REVIEWING`
   - `CONTENT_WAITING_USER_CONFIRM`
   - `IMAGE_PLANNING`
   - `IMAGE_EXECUTING`
   - `IMAGE_REVIEWING`
   - `IMAGE_REPLANNING`
   - `MERGING`
   - `COMPLETED`
   - `FAILED`
2. 扩展 AOP 日志字段：
   - `traceId`
   - `phase`
   - `status`
   - `durationMs`
   - `retryCount`
   - `metadata`
3. 提供按 `taskId` 查询阶段日志的接口。

### 流程

```text
任务开始
  -> 生成 traceId
  -> 每个阶段更新 task status
  -> AOP 记录阶段日志
  -> 阶段成功进入下一状态
  -> 阶段失败进入 FAILED 或等待重试
```

### 交付物

1. 阶段状态枚举。
2. `AgentLog` 扩展或 `metadata` 扩展。
3. 阶段状态更新逻辑。
4. 日志查询接口。

### 检验标准

1. 任意一次生成任务都有唯一 `traceId`。
2. 标题、大纲、正文、配图阶段都能记录耗时和状态。
3. 阶段异常能记录失败原因。
4. 通过接口能查到一个任务的阶段日志。

## 5. 阶段 2：ArticleAgent 阶段化

### 目标

将标题、大纲、正文统一收敛到 `ArticleAgent`，但保留清晰的阶段方法边界。

### 具体功能

1. 新增或重构 `ArticleAgent`。
2. 保留三个阶段方法：
   - `generateTitles`
   - `generateOutline`
   - `generateContent`
3. 定义 `ArticleContext`。
4. 支持用户反馈接入当前阶段。
5. 支持只重跑当前阶段，不重跑整个流程。

### 流程

```text
用户输入主题
  -> ArticleContext 初始化
  -> generateTitles
  -> 用户选择标题或反馈
  -> generateOutline
  -> 用户确认大纲或反馈
  -> generateContent
  -> 用户确认正文或反馈
```

### 交付物

1. `ArticleAgent`。
2. `ArticleContext`。
3. 用户反馈 DTO。
4. 阶段重跑接口或服务方法。

### 检验标准

1. 标题、大纲、正文仍然能独立统计耗时、异常和成功率。
2. 用户对大纲的反馈不会导致标题阶段重复执行。
3. 用户对正文的反馈可以带着已确认标题和大纲重新生成正文。
4. 当前输出质量不低于原流程。

## 6. 阶段 3：JsonStructuredOutputService

### 目标

统一处理结构化输出，解决“只靠 Prompt 输出 JSON 不稳定”的问题。

### 具体功能

1. 实现 JSON 提取。
2. 使用 Gson/Jackson 解析 DTO。
3. 增加 JSON Schema 校验。
4. 增加业务规则校验。
5. 增加 JSON 修复。
6. 增加有限重试。
7. 记录解析、校验、修复、重试指标。

### MVP Schema

| Schema | 对应输出 |
| --- | --- |
| `title_options.schema.json` | 标题候选 |
| `outline_result.schema.json` | 大纲 |
| `image_plan.schema.json` | 配图计划 |
| `review_result.schema.json` | 通用评审结果 |
| `image_review_result.schema.json` | 图片评审结果 |

### 流程

```text
LLM 原始输出
  -> 提取 JSON
  -> 解析 DTO
  -> Schema 校验
  -> 业务规则校验
  -> 失败则 repair
  -> repair 失败则重试原 Agent
  -> 返回 DTO
```

### 交付物

1. `JsonStructuredOutputService`。
2. 第一批 JSON Schema 文件。
3. 业务校验器。
4. JSON 修复逻辑。
5. 指标记录字段。

### 检验标准

1. 模型输出 Markdown 代码块包裹 JSON 时可以正确解析。
2. 字段缺失时能被 Schema 或业务规则拦截。
3. 枚举值错误时能被拦截。
4. 修复失败后最多重试固定次数。
5. 日志中能看到 parse、schema、repair、retry 指标。

## 7. 阶段 4：ReviewAgent

### 目标

建立统一质量评审能力，为重写、重规划和用户确认提供依据。

### 具体功能

1. 定义 `ReviewResult`。
2. 支持标题评审。
3. 支持大纲评审。
4. 支持正文评审。
5. 支持配图计划评审。
6. 支持图片结果评审。
7. 将评分、问题和建议写入日志。

### 流程

```text
阶段输出
  -> ReviewAgent
  -> ReviewResult
  -> score >= 80：进入用户确认或下一阶段
  -> score < 80：进入重写、重规划或 fallback
```

### 交付物

1. `ReviewAgent`。
2. `ReviewResult` DTO。
3. 各阶段评审 Prompt。
4. 各阶段评分 Rubric。
5. 评审日志记录。

### 检验标准

1. 每个被评审阶段都有分数、问题、建议和 nextAction。
2. 评分低于 80 时不会直接进入下一阶段。
3. 评审结果可以被用户看到。
4. 评审结果可以作为重写或重规划输入。

## 8. 阶段 5：ImageAgent 重规划闭环

### 目标

让配图流程从“一次性执行工具”升级为“规划 -> 执行 -> 评审 -> 重规划”的闭环。

### 具体功能

1. `ImageAgent` 输出带 reason 的 `ImagePlan`。
2. `ImageToolExecutor` 执行图片工具。
3. `ReviewAgent` 评审每张图片结果。
4. 不通过时输出 observation 和 revision advice。
5. `ImageAgent` 基于 observation 重规划。
6. 增加最大重试次数。
7. 增加 fallback 策略。

### 流程

```text
ImageAgent 生成 ImagePlan
  -> JsonStructuredOutputService 校验 ImagePlan
  -> ImageToolExecutor 并行执行工具
  -> ReviewAgent 评审图片结果
  -> 通过：进入图文合成
  -> 不通过：ImageAgent 重规划
  -> 超过重试次数：fallback 或等待用户确认
```

### 交付物

1. `ImagePlan` DTO。
2. `ImageToolExecutor`。
3. 图片结果 DTO。
4. 图片评审 Prompt 和 Rubric。
5. observation DTO。
6. 重试和 fallback 策略。

### 检验标准

1. 图片工具失败时不会直接导致整个任务失败。
2. 图片相关性低时可以触发重规划。
3. Mermaid/SVG 渲染失败时可以尝试修复参数。
4. 每张图片最多重规划固定次数。
5. 日志中能看到工具选择理由、执行结果、评审结果和重规划原因。

## 9. 阶段 6：Skill 配置化

### 目标

将写作风格从散落 Prompt 中沉淀为可配置 Skill，方便复用和扩展。

### 具体功能

1. 定义 Skill 配置结构。
2. 迁移现有文章类型和风格 Prompt。
3. `ArticleAgent` 读取 Skill 生成标题、大纲、正文。
4. `ReviewAgent` 读取 Skill 判断风格匹配度。
5. `ImageAgent` 可读取 Skill 决定图片风格。

### 流程

```text
用户选择文章类型或写作风格
  -> SkillService 加载配置
  -> 注入 ArticleContext
  -> ArticleAgent / ReviewAgent / ImageAgent 使用 Skill
```

### 交付物

1. Skill 配置结构。
2. 默认 Skill 配置文件或数据库记录。
3. `SkillService`。
4. Agent 使用 Skill 的上下文注入逻辑。

### 检验标准

1. 新增一种写作风格不需要改 Agent 主流程。
2. 相同主题在不同 Skill 下能生成明显不同的风格。
3. ReviewAgent 能指出风格不匹配问题。

## 10. 阶段 7：生成过程回放

### 目标

让用户和开发者能够查看一篇文章从输入到完成的完整生成过程。

### 具体功能

1. 按 `taskId` 查询完整 trace。
2. 展示阶段状态和耗时。
3. 展示每轮评审结果。
4. 展示用户反馈历史。
5. 展示 JSON 修复和重试记录。
6. 展示图片工具调用和重规划记录。

### 流程

```text
用户打开生成记录
  -> 查询 taskId
  -> 返回阶段时间线
  -> 前端展示每个阶段摘要
```

### 交付物

1. Trace 查询接口。
2. Trace 返回 DTO。
3. 前端基础时间线页面。

### 检验标准

1. 用户能看见标题、大纲、正文、配图的生成记录。
2. 用户能看见每轮 ReviewAgent 的分数和建议。
3. 开发者能定位某次失败发生在哪个阶段。

## 11. 阶段 8：RAG MVP

### 目标

实现可选个人知识库增强，解决用户自有风格、设定、专业资料无法进入创作上下文的问题。

### MVP 范围

只做最小闭环：

1. 用户创建知识库。
2. 用户上传文档。
3. 文档解析、切片、向量化。
4. 用户开启知识库增强。
5. 规则服务决定是否检索。
6. 检索结果注入 `ArticleAgent`。

暂不做：

1. `RetrievalDecisionAgent`。
2. `QueryRewriteAgent`。
3. Reranker。
4. 联网检索。
5. 跨用户知识共享。

### 具体功能

1. 知识库类型：
   - `style_memory`
   - `persona_memory`
   - `domain_knowledge`
   - `project_docs`
   - `task_reference`
2. 文档类型：
   - `.txt`
   - `.md`
   - `.pdf`
   - `.docx`
3. 存储：
   - MySQL 存元数据。
   - COS 或本地文件系统存原始文件。
   - PostgreSQL + pgvector 存 chunk 和 embedding。
4. `RetrievalDecisionService` 使用规则判断是否检索。
5. `ContextBuilder` 将召回内容压缩为写作上下文。

### 流程

```text
用户上传文档
  -> 保存文件
  -> 写入 MySQL 元数据
  -> 异步解析
  -> chunking
  -> embedding
  -> 写入 PGVector

用户创作时开启知识库增强
  -> RetrievalDecisionService
  -> Retriever topK
  -> ContextBuilder
  -> ArticleAgent
```

### 交付物

1. 知识库表。
2. 文档表。
3. PGVector chunk 表。
4. 文档上传接口。
5. 文档解析任务。
6. Retriever。
7. `RetrievalDecisionService`。
8. `ContextBuilder`。

### 检验标准

1. 用户可以上传一份 Markdown 文档并完成索引。
2. 开启知识库增强后，生成内容能引用或体现文档内容。
3. 未开启知识库增强时不触发检索。
4. 不同用户之间不能检索到彼此文档。
5. RAG 失败时不影响普通创作流程。

## 12. 阶段 9：MCP 工具标准化

### 目标

将图片工具接口标准化，降低 `ImageAgent` 和具体工具服务之间的耦合。

### MVP 范围

第一版 MCP 不要求模型直接多轮调用工具，只做工具执行层标准化。

### 具体功能

1. 新增 `McpToolGateway`。
2. 将图片工具封装为标准接口：
   - `search_stock_image`
   - `generate_image`
   - `render_mermaid`
   - `search_icon`
   - `generate_svg_diagram`
   - `upload_asset`
3. `ImageToolExecutor` 通过 `McpToolGateway` 调用工具。
4. 保留原 Java Service 作为 fallback。

### 流程

```text
ImageAgent 生成 ImagePlan
  -> ImageToolExecutor
  -> McpToolGateway
  -> MCP Tool
  -> 具体图片服务
```

### 交付物

1. MCP Tool 定义。
2. `McpToolGateway`。
3. 图片工具 MCP 适配器。
4. fallback 逻辑。

### 检验标准

1. 至少一个图片工具可以通过 MCP 调用成功。
2. MCP 调用失败时可以回退到原 Java Service。
3. 工具输入输出结构统一。
4. 日志能区分 MCP 调用和本地 Service 调用。

## 13. 阶段 10：AgentEval

### 目标

用固定测试集量化系统效果，支撑简历和面试中的效果证明。

### MVP 范围

第一版先做小规模评测，不追求复杂自动化平台。

### 具体功能

1. 准备 20-30 个固定主题。
2. 跑当前版本 baseline。
3. 跑优化后版本 experiment。
4. 收集稳定性、质量、耗时、工具调用指标。
5. 输出 Markdown 报告。

后续再扩展到 50-100 个主题。

### 指标

| 指标类型 | 指标 |
| --- | --- |
| 稳定性 | JSON 首次解析成功率、Schema 通过率、平均重试次数、阶段成功率、fallback 触发率 |
| 质量 | 标题评分、大纲评分、正文评分、配图计划评分、图片结果评分 |
| 耗时 | 平均耗时、P95 耗时、各阶段耗时 |
| 用户交互 | 标题采纳率、大纲修改率、正文重写率、平均确认轮次 |
| 工具 | 各工具调用次数、成功率、平均耗时、图片重规划次数 |

### 流程

```text
读取测试主题
  -> 跑 baseline
  -> 跑 experiment
  -> 收集日志和评审分数
  -> 生成 Markdown 报告
```

### 交付物

1. 测试主题 JSON 文件。
2. baseline 运行脚本。
3. experiment 运行脚本。
4. 指标统计脚本。
5. Markdown 评测报告。

### 检验标准

1. 一键运行评测脚本。
2. 报告能对比 baseline 和 experiment。
3. 报告包含失败案例和重试案例。
4. 报告能给出明确的指标提升或退化。

## 14. 推荐交付顺序

### 第一轮 MVP

只完成最核心闭环：

1. 阶段状态和日志。
2. `ArticleAgent` 阶段化。
3. `JsonStructuredOutputService`。
4. `ReviewAgent`。
5. `ImageAgent` 图片重规划闭环。

完成后，项目就可以回答：

- 为什么这是 Agent，而不是普通 workflow。
- 为什么不是简单堆多个 Agent。
- JSON 结构化输出如何保证稳定。
- 如何通过日志和评分证明效果。

### 第二轮增强

1. Skill 配置化。
2. 生成过程回放页面。
3. RAG MVP。

完成后，项目可以回答：

- 用户个性化风格如何沉淀。
- 专业知识如何进入创作上下文。
- Agent Memory 如何体现。

### 第三轮加分项

1. MCP 工具标准化。
2. AgentEval。
3. RAG Agent 决策增强。

完成后，项目可以回答：

- 工具接口如何标准化。
- 如何量化优化效果。
- Agent 如何自主决定是否使用知识库。

## 15. 不建议第一版做的事

1. 不建议一开始就接入完整 MCP 工具体系。
2. 不建议一开始就做复杂 Agentic RAG。
3. 不建议一开始就做多 Agent 自动辩论。
4. 不建议一开始就做复杂前端监控大屏。
5. 不建议一开始就扩大到 100 个评测主题。
6. 不建议把标题、大纲、正文拆成三个独立 Agent。
7. 不建议让 ReviewAgent 直接预测阅读量或转化率作为核心指标。

第一版最重要的是让系统形成可解释、可观测、可重试的创作闭环。
