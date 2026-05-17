# 阶段 0 基线梳理

## 1. 目标

本文档用于固化当前 `multi-agent-creator` 的真实代码现状，作为后续阶段改造的基线。

阶段 0 只做梳理，不改业务逻辑、不引入新依赖、不调整数据库结构。

## 2. 当前结论

当前项目已经具备完整的图文生成主链路：

```text
创建文章任务
  -> 异步生成标题候选
  -> 用户选择标题并补充要求
  -> 异步生成大纲
  -> 用户确认或编辑大纲
  -> 异步生成正文
  -> 分析配图需求
  -> 执行图片工具
  -> 图文合成
  -> 保存文章
```

当前系统更准确的定位是：

```text
具备多阶段编排、LLM 结构化输出、工具计划生成、后端工具执行、用户确认点和 AOP 日志能力的图文创作工作流。
```

它已经有 Agent 化的基础，但还缺少后续计划中的关键闭环：

- `ReviewAgent` 质量评审。
- JSON Schema 校验、修复和重试。
- 图片结果 observation 回传和 `ImageAgent` 重规划。
- 更细粒度的阶段状态。
- 统一的 traceId、phase、retry、repair、review 指标。

## 3. 当前执行入口

### 3.1 接口入口

| 接口 | 作用 | 后续改造关系 |
| --- | --- | --- |
| `POST /article/create` | 创建文章任务，异步执行标题生成 | 保留，后续增加 traceId 初始化 |
| `GET /article/progress/{taskId}` | SSE 获取生成进度 | 保留，后续增加 Review 和重试事件 |
| `GET /article/{taskId}` | 获取文章详情 | 保留 |
| `POST /article/confirm-title` | 用户确认标题并触发大纲生成 | 保留，后续作为 Human-in-the-loop 节点 |
| `POST /article/confirm-outline` | 用户确认大纲并触发正文和配图生成 | 保留，后续作为 Human-in-the-loop 节点 |
| `POST /article/ai-modify-outline` | AI 修改大纲 | 可复用为阶段反馈能力的一部分 |
| `GET /article/execution-logs/{taskId}` | 查询 Agent 执行日志统计 | 保留，阶段 1 扩展为 trace 查询基础 |

主要代码：

- `src/main/java/com/sxxian/multiagentcreator/controller/ArticleController.java`
- `src/main/java/com/sxxian/multiagentcreator/service/ArticleAsyncService.java`
- `src/main/java/com/sxxian/multiagentcreator/service/impl/ArticleServiceImpl.java`

### 3.2 异步任务入口

`ArticleAsyncService` 将生成流程拆为三个异步阶段：

| 方法 | 当前职责 | 当前阶段状态 |
| --- | --- | --- |
| `executePhase1` | 创建 `ArticleState`，生成标题候选，保存 `titleOptions` | `TITLE_GENERATING` -> `TITLE_SELECTING` |
| `executePhase2` | 读取用户确认标题，生成大纲，保存 outline | `OUTLINE_GENERATING` -> `OUTLINE_EDITING` |
| `executePhase3` | 读取标题和大纲，生成正文、配图需求、图片结果和完整图文 | `CONTENT_GENERATING` -> `COMPLETED` |

`ArticleAsyncService` 支持两种执行模式：

```text
article.agent.orchestrator.enabled=true
  -> ArticleAgentOrchestrator

article.agent.orchestrator.enabled=false
  -> ArticleAgentService
```

## 4. 当前生成链路

### 4.1 StateGraph 编排模式

主要代码：

- `src/main/java/com/sxxian/multiagentcreator/agent/ArticleAgentOrchestrator.java`

当前图结构：

```text
阶段 1：
START
  -> title_generator
  -> END

阶段 2：
START
  -> outline_generator
  -> END

阶段 3：
START
  -> content_generator
  -> image_analyzer
  -> parallel_image_generator
  -> content_merger
  -> END
```

对应节点：

| 节点 | 类 | 输入 | 输出 |
| --- | --- | --- | --- |
| `title_generator` | `TitleGeneratorAgent` | `topic`, `style` | `titleOptions` |
| `outline_generator` | `OutlineGeneratorAgent` | `mainTitle`, `subTitle`, `userDescription`, `style` | `outline` |
| `content_generator` | `ContentGeneratorAgent` | `mainTitle`, `subTitle`, `outline`, `style` | `content` |
| `image_analyzer` | `ImageAnalyzerAgent` | `mainTitle`, `content`, `enabledImageMethods` | `contentWithPlaceholders`, `imageRequirements` |
| `parallel_image_generator` | `ParallelImageGenerator` | `imageRequirements` | `images` |
| `content_merger` | `ContentMergerAgent` | `content`, `images` | `fullContent` |

当前优点：

- 使用 Spring AI Alibaba `StateGraph` 编排，结构清晰。
- 阶段 3 中图片生成支持按 `imageSource` 分组并行执行。
- 通过 `OverAllState` 在节点之间传递中间状态。

当前不足：

- `TitleGeneratorAgent`、`OutlineGeneratorAgent`、`ContentGeneratorAgent` 等节点没有 `@AgentExecution`，不会被当前 AOP 自动记录到 `agent_log`。
- 图是固定顺序，没有条件边、Review 节点、失败回退边、重规划边。
- 只有阶段级方法 `executePhase1/2/3`，没有计划中的 `TITLE_REVIEWING`、`IMAGE_REVIEWING` 等细粒度状态。

### 4.2 旧版 ArticleAgentService 模式

主要代码：

- `src/main/java/com/sxxian/multiagentcreator/service/ArticleAgentService.java`

当前链路：

```text
agent1GenerateTitleOptions
  -> agent2GenerateOutline
  -> agent3GenerateContent
  -> agent4AnalyzeImageRequirements
  -> agent5GenerateImages
  -> mergeImagesIntoContent
```

当前优点：

- 各阶段方法都有 `@AgentExecution` 注解。
- AOP 能记录 agentName、taskId、耗时、状态、输入摘要、输出摘要。
- `aiModifyOutline` 已经提供了大纲阶段的用户反馈重生成雏形。

当前不足：

- 不是 StateGraph 编排，流程扩展性弱于 orchestrator。
- 图片生成是串行执行。
- 仍是固定流程，没有 ReviewAgent 和 observation 重规划。

## 5. 当前数据结构

### 5.1 ArticleState

主要代码：

- `src/main/java/com/sxxian/multiagentcreator/model/dto/article/ArticleState.java`

当前字段：

| 字段 | 说明 | 现状 |
| --- | --- | --- |
| `taskId` | 任务 ID | 已有 |
| `topic` | 选题 | 已有 |
| `userDescription` | 用户补充要求 | 已有 |
| `style` | 文章风格 | 已有 |
| `phase` | 当前阶段 | DTO 中已有，但主流程主要使用 `Article.phase` |
| `titleOptions` | 标题候选 | 已有 |
| `title` | 已确认标题 | 已有 |
| `outline` | 大纲结果 | 已有 |
| `content` | 正文 Markdown 或带占位符正文 | 已有 |
| `imageRequirements` | 配图需求 | 已有 |
| `coverImage` | 封面图 URL | 已有 |
| `images` | 图片结果 | 已有 |
| `enabledImageMethods` | 允许的图片工具 | 已有 |
| `fullContent` | 图文合成结果 | 已有 |

### 5.2 标题 DTO

```java
ArticleState.TitleOption {
    String mainTitle;
    String subTitle;
}

ArticleState.TitleResult {
    String mainTitle;
    String subTitle;
}
```

当前标题生成输出是 `List<TitleOption>`，由 LLM 输出 JSON 后通过 `GsonUtils.fromJson` 解析。

缺口：

- 没有 JSON Schema。
- 没有标题数量、长度、空字段等统一业务校验。
- 没有 ReviewAgent 评分。

### 5.3 大纲 DTO

```java
ArticleState.OutlineResult {
    List<OutlineSection> sections;
}

ArticleState.OutlineSection {
    Integer section;
    String title;
    List<String> points;
}
```

当前大纲生成输出是 `OutlineResult`，由 LLM 输出 JSON 后通过 `GsonUtils.fromJson` 解析。

缺口：

- 没有 JSON Schema。
- 没有章节编号连续性、points 非空等统一校验器。
- 没有 ReviewAgent 评分。

### 5.4 正文 DTO

正文当前不使用 DTO，直接使用 `String content` 保存 Markdown。

当前行为：

- `ContentGeneratorAgent` 生成正文 Markdown。
- `ImageAnalyzerAgent` 会把正文改写为带 `{{IMAGE_PLACEHOLDER_N}}` 的版本。
- `ContentMergerAgent` 将占位符替换为图片 Markdown。

缺口：

- 没有正文格式校验。
- 没有大纲覆盖度校验。
- 没有正文 ReviewAgent 评分。

### 5.5 配图需求 DTO

```java
ArticleState.ImageRequirement {
    Integer position;
    String type;
    String sectionTitle;
    String keywords;
    String imageSource;
    String prompt;
    String placeholderId;
}
```

当前配图需求由 `ImageAnalyzerAgent` 生成，字段含义：

| 字段 | 说明 |
| --- | --- |
| `position` | 图片位置，封面通常为 1 |
| `type` | `cover` 或 `section` |
| `sectionTitle` | 章节标题 |
| `keywords` | 图库或图标检索关键词 |
| `imageSource` | 图片工具类型 |
| `prompt` | AI 生图、Mermaid、SVG 使用的提示词或代码 |
| `placeholderId` | 正文占位符 |

缺口：

- 缺少 `reason`，无法解释为什么选择该工具。
- 缺少 `retryCount`，无法支持重规划计数。
- 没有 JSON Schema。
- 没有 ImagePlan 级别的稳定校验和 Review。

### 5.6 配图结果 DTO

```java
ArticleState.ImageResult {
    Integer position;
    String url;
    String method;
    String keywords;
    String sectionTitle;
    String description;
    String placeholderId;
}
```

内部工具结果：

```java
ImageGenerationTool.ImageGenerationResult {
    Integer position;
    String url;
    String method;
    String keywords;
    String sectionTitle;
    String description;
    String placeholderId;
    boolean success;
    String error;
}
```

缺口：

- `ArticleState.ImageResult` 没有 `success`、`error`、`durationMs`、`fallbackUsed`。
- 图片失败结果在 `ParallelImageGenerator` 中只记录日志，不进入下游 Review。
- 没有图片质量评审结果 DTO。

## 6. 当前持久化结构

### 6.1 article 表

主要实体：

- `src/main/java/com/sxxian/multiagentcreator/model/entity/Article.java`

主要字段：

| 字段 | 说明 |
| --- | --- |
| `taskId` | 文章任务 ID |
| `userId` | 用户 ID |
| `topic` | 选题 |
| `userDescription` | 用户补充要求 |
| `enabledImageMethods` | 允许的配图方式 JSON |
| `style` | 文章风格 |
| `mainTitle` / `subTitle` | 用户确认标题 |
| `titleOptions` | 标题候选 JSON |
| `outline` | 大纲 JSON |
| `content` | 正文 Markdown |
| `fullContent` | 完整图文 Markdown |
| `coverImage` | 封面 URL |
| `images` | 图片结果 JSON |
| `status` | `PENDING` / `PROCESSING` / `COMPLETED` / `FAILED` |
| `phase` | 当前阶段 |
| `errorMessage` | 错误信息 |

### 6.2 当前 ArticlePhaseEnum

当前阶段枚举：

| 阶段 | 说明 |
| --- | --- |
| `PENDING` | 等待处理 |
| `TITLE_GENERATING` | 生成标题中 |
| `TITLE_SELECTING` | 等待选择标题 |
| `OUTLINE_GENERATING` | 生成大纲中 |
| `OUTLINE_EDITING` | 等待编辑大纲 |
| `CONTENT_GENERATING` | 生成正文中 |

与目标架构相比，当前缺少：

- `TITLE_REVIEWING`
- `TITLE_WAITING_USER_CONFIRM`
- `OUTLINE_REVIEWING`
- `OUTLINE_WAITING_USER_CONFIRM`
- `CONTENT_REVIEWING`
- `CONTENT_WAITING_USER_CONFIRM`
- `IMAGE_PLANNING`
- `IMAGE_EXECUTING`
- `IMAGE_REVIEWING`
- `IMAGE_REPLANNING`
- `MERGING`
- `COMPLETED`
- `FAILED`

注意：当前 `COMPLETED` / `FAILED` 存在于 `ArticleStatusEnum`，还没有纳入 `ArticlePhaseEnum`。

## 7. 当前图片工具链路

### 7.1 工具计划生成

当前 `ImageAnalyzerAgent` 让 LLM 输出：

```json
{
  "contentWithPlaceholders": "...",
  "imageRequirements": []
}
```

随后后端执行基础校验：

- 如果用户未限制图片方式，允许全部非 fallback 工具。
- 如果用户限制了图片方式，只保留允许的 `imageSource`。
- 如果 LLM 选择了不允许的工具，会替换为允许列表中的第一个工具。

这属于：

```text
LLM 生成工具调用计划
  -> 后端根据 imageSource 执行工具
```

当前不是模型原生 function calling，也不是 MCP 调用。

### 7.2 工具执行

主要代码：

- `ImageGenerationTool`
- `ImageServiceStrategy`
- `ImageSearchService`
- `ParallelImageGenerator`

当前执行链路：

```text
ParallelImageGenerator
  -> 按 imageSource 分组
  -> CompletableFuture 并行执行不同类型图片
  -> ImageGenerationTool.generateImageDirect
  -> ImageServiceStrategy.getImageAndUpload
  -> ImageSearchService 实现
  -> CosService 上传
  -> ArticleState.ImageResult
```

当前支持的图片方式：

| 方法 | 服务 | 说明 |
| --- | --- | --- |
| `PEXELS` | `PexelsService` | 图库检索 |
| `NANO_BANANA` | `NanoBananaService` | AI 生图 |
| `MERMAID` | `MermaidService` | Mermaid 图表 |
| `ICONIFY` | `IconifyService` | 图标检索 |
| `EMOJI_PACK` | `EmojiPackService` | 表情包检索 |
| `SVG_DIAGRAM` | `SvgDiagramService` | SVG 概念图 |
| `QWEN_IMAGE` | 暂未发现稳定注册的 `ImageSearchService` 实现 | 千问文生图，枚举已存在，但当前服务实现需要后续确认 |
| `PICSUM` | fallback | 随机图降级 |

当前可复用点：

- `ImageSearchService` 已经是工具接口抽象。
- `ImageServiceStrategy` 已经是工具路由层。
- `ImageGenerationTool.generateImageDirect` 可以作为第一阶段 `ImageToolExecutor` 的内部实现。
- `ParallelImageGenerator` 已有并行执行能力。

当前不足：

- 没有 `ImageToolExecutor` 统一命名和工具结果 DTO。
- 工具执行失败只做日志，不形成可评审 observation。
- `ImageServiceStrategy` 内部 fallback 后返回 `PICSUM`，上层不一定知道是否发生过 fallback。
- 没有图片结果质量评分和重规划。

## 8. 当前 JSON 处理

主要工具：

- `src/main/java/com/sxxian/multiagentcreator/utils/GsonUtils.java`
- `src/main/java/com/sxxian/multiagentcreator/config/JsonConfig.java`

当前用途：

| 用途 | 说明 |
| --- | --- |
| LLM 输出解析 | 标题、大纲、配图需求使用 `GsonUtils.fromJson` |
| 数据库存取 | `titleOptions`、`outline`、`images`、`enabledImageMethods` 使用 JSON 字符串 |
| SSE 消息 | 使用 `GsonUtils.toJson` 序列化推送数据 |
| StateGraph 类型转换 | 部分节点用 `GsonUtils.toJson/fromJson` 做 Map 到 DTO 转换 |
| 日志输入输出 | AOP 使用 `GsonUtils.toJson` 保存输入摘要 |

当前 JSON 稳定性：

- 依赖 Prompt 要求模型输出 JSON。
- Gson 解析失败后直接抛异常。
- 没有统一 JSON 提取。
- 没有 Markdown 代码块剥离。
- 没有 JSON Schema。
- 没有统一业务校验。
- 没有修复和有限重试。

`JsonConfig` 是 Jackson HTTP 序列化配置，主要解决 Long 精度问题，不参与 LLM JSON 结构化输出保障。

## 9. 当前日志和观测能力

### 9.1 AgentLog

主要实体：

- `src/main/java/com/sxxian/multiagentcreator/model/entity/AgentLog.java`

当前字段：

| 字段 | 说明 |
| --- | --- |
| `taskId` | 任务 ID |
| `agentName` | 智能体名称 |
| `startTime` | 开始时间 |
| `endTime` | 结束时间 |
| `durationMs` | 耗时 |
| `status` | `RUNNING` / `SUCCESS` / `FAILED` |
| `errorMessage` | 错误信息 |
| `prompt` | 当前记录为类名和方法名 |
| `inputData` | 输入摘要 JSON |
| `outputData` | 输出摘要 JSON |

### 9.2 AOP

主要代码：

- `src/main/java/com/sxxian/multiagentcreator/annotation/AgentExecution.java`
- `src/main/java/com/sxxian/multiagentcreator/aop/AgentExecutionAspect.java`

当前记录方式：

```text
@AgentExecution
  -> 提取 taskId
  -> 提取输入摘要
  -> 执行目标方法
  -> 成功记录 SUCCESS、durationMs、outputData
  -> 失败记录 FAILED、errorMessage
  -> AgentLogService.saveLogAsync
```

当前缺口：

- 注解没有 `phase` 字段。
- 没有 `traceId`。
- 没有 `parentLogId`。
- 没有 `retryCount`。
- 没有 `repairCount`。
- 没有 JSON parse/schema 指标。
- 没有 ReviewAgent 评分字段。
- 没有工具输入、工具输出、observation、fallback 字段。
- StateGraph 节点类没有注解，编排模式下日志覆盖不足。

### 9.3 日志查询

当前接口：

```text
GET /article/execution-logs/{taskId}
```

当前返回：

- `taskId`
- `totalDurationMs`
- `agentCount`
- `agentDurations`
- `overallStatus`
- `logs`

这是阶段 1 生成过程回放的基础，但还不足以承载完整 trace。

## 10. 当前用户确认和反馈能力

当前已有两个明确的 Human-in-the-loop 点：

| 阶段 | 接口 | 行为 |
| --- | --- | --- |
| 标题 | `/article/confirm-title` | 用户从标题候选中选择标题，并可填写 `userDescription` |
| 大纲 | `/article/confirm-outline` | 用户确认或提交编辑后的大纲 |

当前已有一个 AI 反馈能力：

| 阶段 | 接口 | 行为 |
| --- | --- | --- |
| 大纲 | `/article/ai-modify-outline` | 用户输入修改建议，AI 重新生成大纲 |

当前缺口：

- 标题阶段没有“基于反馈重新生成标题”接口。
- 正文阶段没有“用户反馈后重写正文”接口。
- 配图阶段没有“用户确认或反馈图片结果”接口。
- 用户反馈没有独立持久化结构。
- 当前反馈不会进入统一 `ArticleContext.feedbackHistory`。

## 11. 当前能力与目标能力对照

| 能力 | 当前状态 | 目标状态 | 阶段 |
| --- | --- | --- | --- |
| 多阶段文章生成 | 已有 | 保留并标准化为 `ArticleAgent` | 阶段 2 |
| 标题 Human-in-the-loop | 已有选择标题 | 支持评审后确认和反馈重生成 | 阶段 2/4 |
| 大纲 Human-in-the-loop | 已有确认和 AI 修改 | 支持评审后确认和反馈重生成 | 阶段 2/4 |
| 正文 Human-in-the-loop | 缺少 | 支持评审后确认和反馈重写 | 阶段 2/4 |
| StateGraph 编排 | 已有 | 增加 Review、重试、重规划边 | 阶段 4/5 |
| AOP 日志 | 旧路径较完整，新编排路径不足 | 所有 Agent/阶段统一记录 | 阶段 1 |
| JSON 解析 | Gson 直接解析 | 提取、解析、Schema、业务校验、修复、重试 | 阶段 3 |
| ReviewAgent | 缺少 | 统一评审标题、大纲、正文、图片 | 阶段 4 |
| 图片工具计划 | 已有 | 增加 reason、retryCount、ImagePlan Schema | 阶段 5 |
| 图片工具执行 | 已有 | 抽象为 `ImageToolExecutor`，输出标准结果 | 阶段 5 |
| 图片 observation 重规划 | 缺少 | ReviewAgent 输出 observation，ImageAgent 重规划 | 阶段 5 |
| Skill 配置化 | 目前是枚举 + Prompt 常量 | 可配置 Skill | 阶段 6 |
| RAG | 未实现 | 可选知识库增强 | 阶段 8 |
| MCP | 未实现 | 工具标准化层，后置增强 | 阶段 9 |
| AgentEval | 未实现 | 离线指标报告 | 阶段 10 |

## 12. 可复用模块清单

### 12.1 直接复用

| 模块 | 复用方式 |
| --- | --- |
| `ArticleController` | 保留接口入口，逐步新增确认和反馈接口 |
| `ArticleAsyncService` | 保留异步任务模式，后续拆更细状态 |
| `ArticleServiceImpl` | 保留文章任务创建、状态更新、保存逻辑 |
| `ArticleState` | 作为短期记忆基础，后续扩展为 `ArticleContext` |
| `ArticlePhaseEnum` | 扩展阶段枚举 |
| `ArticleStatusEnum` | 保留任务级状态 |
| `SseEmitterManager` | 保留流式进度推送 |
| `GsonUtils` | 可作为结构化服务底层解析工具之一 |
| `AgentLogService` | 扩展为 trace 查询基础 |
| `ImageSearchService` | 保留为图片服务抽象 |
| `ImageServiceStrategy` | 保留为第一阶段 ToolGateway |
| `CosService` | 保留为图片上传能力 |

### 12.2 改名或升级复用

| 当前模块 | 建议目标 | 说明 |
| --- | --- | --- |
| `TitleGeneratorAgent` | `ArticleAgent.generateTitles` 或 ArticleAgent 内部组件 | 文本能力收敛 |
| `OutlineGeneratorAgent` | `ArticleAgent.generateOutline` 或 ArticleAgent 内部组件 | 文本能力收敛 |
| `ContentGeneratorAgent` | `ArticleAgent.generateContent` 或 ArticleAgent 内部组件 | 文本能力收敛 |
| `ImageAnalyzerAgent` | `ImageAgent.generateImagePlan` | 增加 reason、retryCount、review 输入 |
| `ParallelImageGenerator` | `ImageToolExecutor` | 保留并行能力，输出标准工具结果 |
| `ContentMergerAgent` | `ContentMergeService` 或保留为合成节点 | 可继续作为后处理节点 |
| `ArticleAgentService.aiModifyOutline` | 阶段反馈重生成能力 | 可扩展到标题和正文 |

### 12.3 新增模块

| 模块 | 阶段 | 说明 |
| --- | --- | --- |
| `JsonStructuredOutputService` | 阶段 3 | 结构化输出稳定性 |
| `ReviewAgent` | 阶段 4 | 质量评审 |
| `ReviewResult` | 阶段 4 | 统一评审结果 DTO |
| `ImagePlan` | 阶段 5 | 标准配图计划 DTO |
| `ImageToolResult` | 阶段 5 | 标准图片工具执行结果 |
| `ImageReviewResult` | 阶段 5 | 图片评审和 observation |
| `UserFeedback` | 阶段 2/4 | 用户反馈记录 |
| `GenerationTraceService` | 阶段 7 | 生成过程回放 |

## 13. 当前问题清单

### 13.1 Agent 边界问题

当前 `TitleGeneratorAgent`、`OutlineGeneratorAgent`、`ContentGeneratorAgent` 都是文本生成能力，拆成多个 Agent 容易被质疑为“多个节点的 workflow”。后续应收敛为一个 `ArticleAgent` 的多个阶段方法，保留阶段边界和 AOP 观测。

### 13.2 缺少评审闭环

当前生成结果直接进入用户确认或下一阶段，没有 `ReviewAgent` 评审：

- 标题候选没有质量评分。
- 大纲没有结构和覆盖度评审。
- 正文没有大纲覆盖度和风格一致性评审。
- 图片结果没有相关性和可用性评审。

### 13.3 JSON 结构化输出不稳定

当前主要依赖 Prompt + Gson：

- 模型输出解释文本会解析失败。
- 模型输出 Markdown 代码块会解析失败。
- 字段缺失和枚举错误不能被统一识别。
- 没有修复和重试。

### 13.4 图片工具还没有形成 Agent 闭环

当前图片阶段是：

```text
LLM 生成 imageRequirements
  -> 后端执行工具
  -> 成功则合成，失败则跳过或 fallback
```

后续目标是：

```text
ImageAgent 生成 ImagePlan
  -> ImageToolExecutor 执行
  -> ReviewAgent 评审
  -> 不通过则 observation 回传 ImageAgent
  -> ImageAgent 重规划
```

### 13.5 日志无法支撑完整回放

当前日志能看执行耗时，但无法完整回答：

- 当前处于哪个细分阶段。
- 是否发生 JSON 修复。
- 是否发生 retry。
- ReviewAgent 评分是多少。
- 图片工具为什么选择该方法。
- 图片失败后为什么 fallback。

### 13.6 编排模式和 AOP 模式存在割裂

当前新编排模式更适合后续 Agent 架构，但 AOP 覆盖不如旧服务模式。阶段 1 需要统一日志方案，避免后续只在某一种执行模式下可观测。

## 14. 阶段 1 输入建议

阶段 1 应优先处理日志和阶段状态，不建议立即改生成质量。

建议任务：

1. 扩展 `ArticlePhaseEnum`，补齐目标阶段状态。
2. 扩展 `@AgentExecution`，增加 `phase` 字段。
3. 给 StateGraph 节点或 orchestrator 阶段补上统一日志记录。
4. 增加 `traceId`，短期可复用 `taskId`，后续再独立。
5. 扩展 `AgentLog` 的 `metadata` 字段，先避免频繁加列。
6. 记录 JSON parse 成功/失败、工具调用结果、fallback 信息到 `metadata`。
7. 保持原接口兼容，不影响当前前端流程。

## 15. 阶段 0 验收

阶段 0 完成后，应满足：

1. 能说明当前项目如何从主题生成完整图文。
2. 能说明当前 DTO、Agent、Service、日志分别在哪里。
3. 能说明哪些能力已经存在，哪些只是计划。
4. 能说明后续阶段 1 应该从哪里动手。
5. 未改业务代码。
6. 未引入新依赖。
