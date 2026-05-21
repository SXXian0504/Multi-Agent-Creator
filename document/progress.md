# 项目优化进度日志

本文档用于沉淀多阶段优化进展。较早阶段只保留关键结论和交付物，最近阶段保留较完整的问题背景、修改内容和遗留事项。

## 阶段 0：基线梳理

参考文档：

- `document/plan.md`
- `document/stage0_baseline.md`

阶段目标是理解既有文章生成链路、前后端交互方式、Agent 日志和图片生成路径，为后续分阶段改造确定边界。

关键原则：

- 不重写主业务流程。
- 不引入不必要的新依赖。
- 优先复用现有 `ArticleAgentService`、StateGraph 编排、SSE、结构化 DTO 和前端页面。
- 每阶段以可编译、可回退、局部闭环为目标。

## 阶段 1：阶段状态与 Agent 日志

完成内容：

- 扩展 `ArticlePhaseEnum`，补齐标题、大纲、正文、图片规划、图片执行、图片审核、合并、完成、失败等细粒度阶段。
- 扩展 `AgentLog`、`AgentExecutionStats`、`AgentLogServiceImpl`，支持：
  - `traceId`
  - `phase`
  - `retryCount`
  - `metadata`
  - `phaseDurations`
- 扩展 `@AgentExecution` 和 `AgentExecutionAspect`，让 Agent 方法执行时写入阶段和元数据。
- 在异步文章生成流程中补充关键阶段推进。
- 兼容旧确认接口状态：
  - 标题确认继续兼容 `TITLE_SELECTING`
  - 大纲确认/AI 修改继续兼容 `OUTLINE_EDITING`
- 补充数据库迁移：
  - `sql/add_agent_log_trace_fields.sql`
  - `sql/create_table.sql`

交付结果：

- 后端具备更细粒度任务阶段状态。
- `/article/execution-logs/{taskId}` 可聚合阶段耗时。
- 旧前端确认流程未被破坏。

## 阶段 2：ArticleAgent 阶段化

完成内容：

- 新增统一文本 Agent：`ArticleAgent`。
- 新增文章上下文 DTO：`ArticleContext`。
- 新增用户反馈 DTO：`UserFeedback`。
- 将标题、大纲、正文生成收敛到统一阶段方法：
  - `generateTitles`
  - `generateOutline`
  - `generateContent`
- 旧 `ArticleAgentService` 路径接入统一 `ArticleAgent`。
- StateGraph 路径中标题、大纲、正文节点也替换为统一 `ArticleAgent` 调用。
- AOP 支持从 `ArticleContext` 和 `OverAllState` 提取 `taskId` 和输入摘要。

交付结果：

- 文本生成阶段边界更清晰。
- 为后续“只重跑当前阶段”和“用户反馈后局部重写”预留能力。
- 图片规划、图片执行、图文合并当时仍沿用既有链路。

## 阶段 3：JsonStructuredOutputService

完成内容：

- 新增统一结构化输出服务：`JsonStructuredOutputService`。
- 支持：
  - Markdown 代码块 JSON 提取
  - 混杂解释文本中的 JSON 提取
  - Gson 强类型解析
  - 根类型校验
  - 业务规则校验
  - 有限重试
  - 修复指标记录
- 新增结构化输出类型：
  - `TITLE_OPTIONS`
  - `OUTLINE_RESULT`
  - `IMAGE_PLAN`
  - `REVIEW_RESULT`
  - `IMAGE_REVIEW_RESULT`
- 新增 JSON Schema 文档，作为结构契约沉淀。
- 接入标题、大纲、AI 修改大纲、配图计划等解析路径。
- 新增 `StructuredOutputTraceContext`，并把结构化输出指标写入 Agent 日志 metadata。
- 补充 `JsonStructuredOutputServiceTest`。

交付结果：

- LLM JSON 输出不稳定的问题被集中处理。
- 结构化解析失败能被明确定位和重试。
- 后续 ReviewAgent 与图片规划都复用同一套解析能力。

## 阶段 4：ReviewAgent 与文本评审闭环

完成内容：

- 前端创作页补齐“商品营销”风格。
- 新增字数范围选择：
  - 自动
  - 短文 200-500 字
  - 中篇 800-1500 字
  - 长文 2000-3500 字
- 后端新增 `wordRange` 字段并同步 DTO、VO、Entity、SQL。
- 移除正文 Prompt 中固定“2000 字左右”等硬编码限制。
- ReviewAgent 接入标题、大纲、正文评审。
- 低于通过阈值时，ReviewAgent 生成修订建议并回流给当前阶段重写。
- 结构化输出服务增强 ReviewResult 容错：
  - 支持单元素数组包装
  - 支持 `{ "review_result": {...} }` 命名包装
- 文本评审模型与图片评审模型拆分：
  - `article.review.text-model`
  - `article.review.image-model`

交付结果：

- 标题、大纲、正文具备基础自动评审与当前阶段重写能力。
- 文本评审不再误走视觉模型。
- 正文二次低分时不会直接阻断整篇文章任务，而是保留结果和评审建议。

遗留问题：

- 图片结果评审当时仍不稳定。
- 图片审核失败后的重新配图闭环仍未完整实现。

## 阶段 4 后续修复：大纲篇幅与评审质量

完成内容：

- 大纲生成接入字数范围和风格约束。
- 移除大纲 Prompt 中固定 3-5 个观点、适合 2000 字文章等暗示。
- 不同字数范围给出不同章节建议：
  - `short`：2-3 章，每章 1-2 个要点，禁止默认生成 5 章。
  - `medium`：4-5 章。
  - `long`：5-7 章。
  - 未选择字数且为 `marketing` 风格时，默认采用 2-4 章短转化结构。
- 强化 ReviewAgent 文本评审 Prompt：
  - 增加内容画像。
  - 禁止固定输出 85 分。
  - 要求问题和建议引用具体标题、章节、段落、卖点或论据。
  - 文本评审温度调整为 `0.0`。
- 结构化服务增加文本评审业务校验：
  - `dimensionScores` 必须包含 `commonBaseline/styleFit/stageFit`
  - 三项之和必须等于 `score`
  - `score < 95` 时 `problems/suggestions` 不得为空

交付结果：

- 短文和营销文更不容易被大纲阶段扩写成过长结构。
- ReviewAgent 文本评分更可解释，减少固定分和泛化建议。

## 阶段 5：配图规划、并行生成与图文合并

阶段 5 增强后，正文生成和配图生成可以在用户确认大纲后并行执行。

相关能力包括：

- `ImageAgent`
- `ImageAnalyzerAgent`
- `ImageToolExecutor`
- `ParallelImageGenerator`
- `ContentMergerAgent`
- 图片规划 schema 扩展
- 图片执行 trace
- 图片结果审核 DTO

当前已完成重点：

- 后端能规划配图需求。
- 后端能按图片来源/工具执行图片生成或检索。
- 后端能收集图片执行 trace 和图片审核结果。
- 图文合并支持图片占位符与最终内容合并。
- SSE 能向前端推送阶段完成、图片完成、合并完成等事件。

## 2026-05-18 阶段 5 修复：前端配图占位符展示

### 问题背景

阶段 5 增强后，验收发现：

1. 图片生成完成后，前端仍可能看不到配图。
2. 并行图片分支触发 `AGENT4_COMPLETE` / `AGENT5_COMPLETE` 时，SSE 构造消息读取的是主 `ArticleState`，当时主状态可能还没回填 `imageRequirements` / `images`。
3. 大纲后配图规划不再依赖正文预置占位符，最终正文里可能存在 `{{IMAGE_PLACEHOLDER_N}}`，前端需要能按 `images.placeholderId` 渲染成图片。
4. `position=1` 曾被当成封面图跳过正文合并，导致正文区域看不到第一张图。

### 执行方案

采用低风险补齐方案，不改图片生成和重规划核心逻辑，只补齐最终展示链路：

1. 后端保留 `fullContent` 作为最终图文内容。
2. `MERGE_COMPLETE` SSE 同时返回：
   - `fullContent`
   - `images`
   - `coverImage`
3. 后端 `ArticleVO.ImageItem` 暴露 `placeholderId`，让详情页加载历史文章时也能基于占位符渲染图片。
4. 前端 Markdown 渲染前增加占位符解析：
   - 遍历 `images`
   - 查找带 `placeholderId` 和 `url` 的图片
   - 将 `{{IMAGE_PLACEHOLDER_N}}` / `{{ICON_PLACEHOLDER_N}}` 替换为 Markdown 图片语法
   - 再交给 `marked` 渲染为 HTML
5. 创建页收到单张 `IMAGE_COMPLETE` 时临时累积图片，收到 `MERGE_COMPLETE` 后用后端最终 `images` 覆盖。
6. 后端图文合并时补齐占位符插入逻辑，避免正文没有占位符导致图片无法落位。
7. `ContentMergerAgent` 对 `position=1` 的图片不再简单跳过正文展示，而是作为封面/首图补入最终内容。

### 修改文件

后端：

- `src/main/java/com/sxxian/multiagentcreator/agent/agents/ContentMergerAgent.java`
- `src/main/java/com/sxxian/multiagentcreator/agent/ArticleAgentOrchestrator.java`
- `src/main/java/com/sxxian/multiagentcreator/service/ArticleAsyncService.java`
- `src/main/java/com/sxxian/multiagentcreator/service/ArticleAgentService.java`
- `src/main/java/com/sxxian/multiagentcreator/model/vo/ArticleVO.java`

前端：

- `multi-agent-creator/src/utils/markdown.ts`
- `multi-agent-creator/src/pages/article/ArticleCreatePage.vue`
- `multi-agent-creator/src/pages/article/ArticleDetailPage.vue`
- `multi-agent-creator/src/pages/article/components/CompletedState.vue`
- `multi-agent-creator/src/api/typings.d.ts`

测试：

- `src/test/java/com/sxxian/multiagentcreator/agent/agents/ContentMergerAgentTest.java`

### 验证结果

执行过：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q -DskipTests compile
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dtest=ContentMergerAgentTest,JsonStructuredOutputServiceTest" test

cd multi-agent-creator
npm run type-check
```

结果：

- 后端编译通过。
- `ContentMergerAgentTest` 通过。
- `JsonStructuredOutputServiceTest` 通过。
- 前端 `vue-tsc --build` 类型检查通过。

### 当前状态

- 图文合并问题已解决。
- 前端创建页、完成态、详情页都通过统一 `articleMarkdownToHtml(...)` 支持占位符替换。
- 如果后端已经把占位符替换成 Markdown 图片，前端可直接渲染。
- 如果后端返回的 `fullContent` 仍包含占位符，前端会基于 `images.placeholderId` 替换后渲染。

## 2026-05-18 阶段 5 修复：ReviewAgent 图片审核模型链路

### 问题背景

图文合并与前端配图展示问题修复后，继续处理 ReviewAgent 图片审核链路。

运行现象：

- `qwen-vl-plus-latest` 在百炼后台调用失败率为 100%。
- `qwen3-vl-32b-thinking` 通过 Spring AI `Media` 传图时返回：
  - `HTTP 400 InvalidParameter: url error, please check url`
- 纯文本降级无法完成“图片视觉内容是否匹配文章片段/配图需求”的审核目标。

### 执行方案

1. 图片审核绕开 Spring AI `Media` 适配层。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/agent/ReviewAgent.java`
   - 后端先下载图片字节。
   - 构造 `data:image/...;base64,...`。
   - 直接调用百炼 OpenAI-compatible 接口：
     - `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
   - 请求内容使用 `image_url.url = data:image/jpeg;base64,...`，不再让百炼自行拉取 COS URL。

2. 图片审核模型默认切换为 `qwen-vl-plus`。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/agent/ReviewAgent.java`
     - `src/main/resources/application.yml`
     - `src/main/resources/application-local.yml.example`
   - 当前默认配置：
     - `article.review.image-model=qwen-vl-plus`
     - `article.review.image-fallback-models=qwen3.5-plus,qwen3.5-omni-plus`
   - 原因：
     - 实测中 `qwen3.5-plus` 和 `qwen3.5-omni-plus` 调用失败。
     - `qwen-vl-plus` 成功返回图片审核结果，并能识别图片实际内容与配图需求不匹配的问题。

3. 增加视觉模型成功日志。
   - 成功时打印：
     - `图片评审多模态调用成功, model=..., url=...`
   - 目的：后续排查时可以直接确认最终由哪个模型产出了评审结果。

4. 保留视觉模型 fallback 链路。
   - 当前尝试顺序：
     - `qwen-vl-plus`
     - `qwen3.5-plus`
     - `qwen3.5-omni-plus`
   - 所有视觉模型失败后，才降级为文本审核。
   - 文本审核只能基于 `imageResult` 元数据判断，不能替代真实视觉审核。

### 验证结果

执行过：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q -DskipTests compile
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dtest=ContentMergerAgentTest,JsonStructuredOutputServiceTest" test
```

结果：

- 后端编译通过。
- `ContentMergerAgentTest` 通过。
- `JsonStructuredOutputServiceTest` 通过。
- 本地 Maven 编译期间偶发 `target/classes/application-local.yml.example` 资源复制权限问题，删除旧 target 资源文件后可继续编译。

## 2026-05-18 阶段 5 修复：百炼 wanx-v1 文生图接入

### 问题背景

Nano Banana 文生图因为额度问题无法作为当前稳定路径，需要改成百炼大模型接入的文生图能力。当前优先使用 `wanx-v1`，原因是该模型有 500 次免费额度，适合作为阶段 5 的文生图主路径。

### 执行方案

1. 新增 `QwenImageService`，注册为 `ImageSearchService` 的 `QWEN_IMAGE` 实现。
2. `QWEN_IMAGE` 默认调用百炼 / DashScope 异步文生图接口：
   - 提交任务：`/api/v1/services/aigc/text2image/image-synthesis`
   - 查询任务：`/api/v1/tasks/{task_id}`
   - 请求头使用 `X-DashScope-Async: enable`
3. 默认模型改为 `wanx-v1`，配置项位于 `qwen.image.*`：
   - `model`
   - `submit-endpoint`
   - `task-endpoint`
   - `width`
   - `height`
   - `n`
   - `style`
   - `poll-interval`
   - `max-poll-attempts`
4. API Key 优先读取 `qwen.image.api-key`，未配置时复用 `spring.ai.dashscope.api-key`。
5. 文生图生成成功后返回临时图片 URL，再复用现有 `ImageServiceStrategy -> CosService` 上传到 COS。
6. 将默认 AI 生图方式从 `NANO_BANANA` 切换为 `QWEN_IMAGE`。
7. ImageAgent / ImageAnalyzerAgent / ArticleAgentService 的默认可用配图方式改为包含 `QWEN_IMAGE`，不再默认推荐 `NANO_BANANA`。
8. 前端创建页把 VIP AI 生图选项从 `NANO_BANANA` 改为 `QWEN_IMAGE`，展示文案改为“百炼文生图”。
9. `NANO_BANANA` 旧实现保留，避免破坏历史枚举和已有兼容路径，但当前不再作为默认文生图入口。

### 修改文件

- `src/main/java/com/sxxian/multiagentcreator/service/QwenImageService.java`
- `src/main/java/com/sxxian/multiagentcreator/config/QwenwConfig.java`
- `src/main/java/com/sxxian/multiagentcreator/model/enums/ImageMethodEnum.java`
- `src/main/java/com/sxxian/multiagentcreator/agent/ImageAgent.java`
- `src/main/java/com/sxxian/multiagentcreator/agent/agents/ImageAnalyzerAgent.java`
- `src/main/java/com/sxxian/multiagentcreator/service/ArticleAgentService.java`
- `src/main/java/com/sxxian/multiagentcreator/constant/PromptConstant.java`
- `src/main/java/com/sxxian/multiagentcreator/agent/tools/ImageGenerationTool.java`
- `src/main/java/com/sxxian/multiagentcreator/service/impl/ArticleServiceImpl.java`
- `multi-agent-creator/src/pages/article/ArticleCreatePage.vue`
- `multi-agent-creator/src/pages/VipPage.vue`
- `src/main/resources/application.yml`
- `src/main/resources/application-local.yml.example`
- `src/main/resources/application-prod.example`

### 验证结果

执行过：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q -DskipTests compile
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dtest=ContentMergerAgentTest,JsonStructuredOutputServiceTest" test

cd multi-agent-creator
npm run type-check
```

结果：

- 后端编译通过。
- `ContentMergerAgentTest` 通过。
- `JsonStructuredOutputServiceTest` 通过。
- 前端 `vue-tsc --build` 类型检查通过。

### 仍需运行确认

- 当前环境没有执行真实百炼联网调用，`wanx-v1` 的实际返回结构、图片 URL 有效期、COS 下载上传链路需要用真实 `DASHSCOPE_API_KEY` 跑一次端到端任务确认。
- 如果百炼返回字段与当前假设不同，需要只调整 `QwenImageService.extractImageUrl(...)` 和任务状态解析逻辑。

## 2026-05-18 阶段 5 修复：Graphviz 流程图配图接入

### 背景

Mermaid 对 LLM 生成的复杂节点文本较敏感，遇到换行、括号、特殊字符时容易解析失败。为降低流程图生成失败率，新增免费本地工具链 `GRAPHVIZ`：LLM 生成 DOT，后端调用 Graphviz `dot -Tsvg` 渲染，再复用现有 `ImageData -> CosService` 上传链路。

### 已完成

1. 新增 `ImageMethodEnum.GRAPHVIZ`，并在 `ImageServiceStrategy` 中路由到 `graphviz` COS 文件夹。
2. 新增 `GraphvizConfig`，支持配置：
   - `graphviz.cli-command`
   - `graphviz.output-format`
   - `graphviz.background-color`
   - `graphviz.timeout`
3. 新增 `GraphvizService`：
   - 实现 `ImageSearchService`
   - 从 `ImageRequest.prompt` 读取 DOT 代码
   - 支持去除 Markdown fence
   - 校验 DOT 必须以 `digraph` / `graph` / `strict digraph` / `strict graph` 开头
   - 使用 `ProcessBuilder` 调用本地 `dot`
   - 生成 `image/svg+xml` 后走现有 COS 上传链路
4. `ImageAgent` / `ImageAnalyzerAgent` / `ArticleAgentService` 默认可用配图方式加入 `GRAPHVIZ`，并明确流程图、架构图优先使用 Graphviz，Mermaid 保留为补充和回退。
5. `ImageToolExecutor` 针对 `GRAPHVIZ` / `MERMAID` 的特殊降级做了处理：如果底层策略因为渲染失败返回 `PICSUM`，执行器会把它视为图表工具失败，并进入单图重规划，而不是直接拿随机图参与审核。
6. 重规划提示已补充：
   - `GRAPHVIZ` 渲染失败时优先切换为 `MERMAID` 并重写 Mermaid 代码
   - `MERMAID` 渲染失败时优先切换为 `GRAPHVIZ` 并重写 DOT 代码
7. 前端创建页新增 `Graphviz` 配图方式选项；普通用户默认可用方法也加入 `GRAPHVIZ`。
8. JSON Schema 已允许 `imageSource=GRAPHVIZ`。

### 验证结果

已执行通过：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dmaven.resources.skip=true" "-Dtest=GraphvizServiceTest,MermaidServiceTest,JsonStructuredOutputServiceTest" test
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dmaven.resources.skip=true" -DskipTests compile
```

说明：
- 当前本机 PATH 中未找到 `dot`，真实 Graphviz 渲染需要安装 Graphviz，或把 `graphviz.cli-command` 配置为 `dot.exe` 完整路径。
- 未跳过资源复制的 Maven 流程仍会被 `target/classes/application-local.yml.example` 的 `AccessDeniedException` 阻塞，和本次 Java 编译结果无关。

## 当前仍存在的问题

### 1. 图片审核不通过之后的处理方案仍需补齐

当前行为：

- `ReviewAgent.reviewImageResult(...)` 能返回：
  - `approved=false`
  - `score`
  - `observation`
  - `revisionAdvice`
  - `nextAction`
- `ImageToolExecutor` 已有按单张图片重试/重规划的基础代码路径。

仍需确认和完善：

- 当 `approved=false` 且 `nextAction=REPLAN` 时，是否一定重写该图片的 `prompt` / `keywords` / `imageSource`。
- 每张图最多重试次数应该固定为 1 次还是 2 次。
- 多次失败后是保留最佳得分图片、使用 fallback 搜图，还是把问题展示给用户确认。
- 最终 SSE / 前端展示中，需要明确告诉用户哪些图片未通过审核、原因是什么、是否已使用替代图。

建议实现：

- 输入：原 `ImageRequirement`、原 `ImageResult`、`ImageReviewResult.observation`、`ImageReviewResult.revisionAdvice`。
- 输出：更新后的 `prompt`、`keywords`、`imageSource`。
- 每张图限制最多 1-2 次重试。
- 所有尝试失败后保留最佳结果，并把审核问题展示给用户。

### 2. 文生图功能已接入，但仍需真实链路验收

当前状态：

- 系统已有多种图片来源/工具路径。
- 文生图主路径已切换为 `QWEN_IMAGE` / `wanx-v1`。
- 生成结果会复用现有 COS 上传链路。
- 当前图片审核已经能发现“图片与文章需求不匹配”。
- `NANO_BANANA` 旧实现保留，但不再作为默认文生图入口。

仍需补齐：

- 使用真实百炼额度跑端到端任务，确认 `wanx-v1` 提交任务、轮询、图片下载、COS 上传全部可用。
- 根据真实生成质量继续优化 prompt 规范。
- 当 ReviewAgent 判定图片不匹配时，完善重规划后的文生图 retry 策略。
- 明确多次文生图失败后的 fallback 策略，是切 Pexels、保留最佳图，还是展示给用户确认。

建议：

- 高相关性、概念型、计划型配图优先走文生图。
- 真实场景、人物、商品、表情包等素材型需求优先走搜图。
- Mermaid/SVG/Iconify 适合结构图、流程图、图标和概念示意。

## 通用验证与运行注意事项

- 本地后端编译需要显式使用 JDK 21：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
```

- 本地 Maven 路径：

```powershell
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd
```

- `mvnw.cmd` 曾因网络问题无法下载 wrapper，当前验证优先使用本机已有 Maven。
- 偶发 `target/classes/application-local.yml.example` 资源复制权限问题时，删除旧 target 资源文件后可继续编译。
## 2026-05-18 阶段 5 修复：Graphviz 图表可读性与展示优化

### 背景

真实生成验证中，Graphviz 流程图可以生成并通过审核，但文章页展示效果差：SVG 被前端高度上限压缩，导致细节过小；同时 LLM 输出的 DOT 缺少统一视觉规范，容易出现巨大留白、纵向过长、节点太小、排版朴素的问题。

### 已完成

1. `GraphvizService.sanitizeDotCode(...)` 增加默认样式注入：横向阅读 `rankdir=LR`、收紧节点/层级间距、正交连线、圆角浅色节点、蓝色边框、大字号中文兼容字体。
2. `GraphvizService` 放宽 DOT 起始校验，支持 `digraph{...}` / `graph{...}` 这类没有空格但合法的 DOT 写法。
3. `ImageAgent` / `ArticleAgentService` / `ImageAnalyzerAgent` 的 Graphviz 使用说明已加强：只输出 DOT，不要 markdown fence；优先 `digraph + rankdir=LR`；长中文标签用 `\n` 拆行；节点数量建议控制在 8 个以内，避免巨大留白。
4. 前端文章预览和详情页 SVG 展示优化：SVG 按文章宽度展示，移除 500px 高度上限；图片段落取消首行缩进；增加白底、浅边框和轻量阴影。覆盖创建页完成预览、文章详情页、`CompletedState` 组件。

### 验证结果

已执行通过：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dmaven.resources.skip=true" "-Dtest=GraphvizServiceTest" test
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dmaven.resources.skip=true" -DskipTests compile

cd multi-agent-creator
npm run type-check
```

## 2026-05-18 阶段 5 修复：Graphviz 清洗导致语法错误的兜底

### 问题背景

运行中出现 Graphviz 渲染失败：

```text
Graphviz CLI exitCode=1
syntax error in line 34 near ';'
```

这类错误可能来自两种情况：

- LLM 生成的 DOT 本身存在非法语法。
- 后端清理 `size/rankdir/pos/pin` 等布局属性时，把属性列表改成空列表或留下残余符号，导致原本可解析的 DOT 变成非法 DOT。

### 已完成

1. `GraphvizService` 的布局属性清理后增加属性列表规范化：
   - 清理残留逗号。
   - 移除空的 `graph []` / `node []` / `edge []`。
   - 将节点空属性列表 `A []` 规范化为 `A;`。
2. Graphviz CLI 首次渲染失败时，自动使用“只注入默认样式、不清理布局属性”的 DOT 重试一次，避免清理器误伤直接降级。
3. Graphviz CLI 最终失败时，日志会输出带行号的清洗后 DOT 摘要，下一次可以直接定位报错行附近的 DOT 内容。
4. `GraphvizServiceTest` 增加多行 `graph [...]`、节点 `pos/pin`、空属性列表规范化场景。

### 验证结果

已执行通过：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dmaven.resources.skip=true" "-Dtest=GraphvizServiceTest" test
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dmaven.resources.skip=true" -DskipTests compile
```

### 待确认

- 需要用真实文章再跑一轮端到端生成，确认新的 Graphviz 默认样式对实际 DOT 的效果是否足够稳定。
- 如果仍然出现复杂流程图不美观，后续可增加 DOT 复杂度检查，或增加“结构化 JSON -> 固定 SVG 模板”的专用图表渲染器，比纯 Graphviz 更容易稳定控制美观度。

## 2026-05-18 阶段 5 修复：配图进度状态与封面 Graphviz 样式

### 问题背景

并行生成模式下，配图分支可能先于正文分支完成规划。前端收到 `AGENT4_COMPLETE` 后进入“生成配图”，但随后正文分支的 `AGENT3_COMPLETE` 又把 `currentStep` 回退到“分析配图”，导致左侧状态栏滞后，中间配图进度条不显示。

同时，正文中的 Graphviz 流程图已经较清晰，但封面流程图仍可能出现巨大空白和节点分散。原因是 LLM 生成的 DOT 可能自带 `size`、`ratio`、`rankdir`、`nodesep`、`ranksep`、`pos` 等布局属性，覆盖或干扰后端统一样式。

### 已完成

1. 前端 `ArticleCreatePage.vue` 新增 `advanceStep(step)`，步骤推进改为单调递增，避免并行事件乱序导致左侧状态回退。
2. `AGENT4_COMPLETE` 收到后立即进入“生成配图”，并把进度设为最小 1%，让用户能看到配图生成阶段已经开始。
3. `IMAGE_COMPLETE` 也会兜底推进到“生成配图”，即使 `AGENT4_COMPLETE` 被乱序覆盖，单张图片完成后仍能显示正确阶段和进度。
4. `GraphvizService.sanitizeDotCode(...)` 增加布局覆盖清洗：
   - 移除 `rankdir/size/ratio/page/viewport/bb/margin/pad/nodesep/ranksep/splines/overlap/outputorder`
   - 移除节点绝对位置类属性 `pos/pin`
   - 再注入统一 Graphviz 默认样式，保证封面和正文图表都走同一套视觉约束。
5. 补充 `GraphvizServiceTest` 覆盖 LLM 自带布局属性清洗场景。

### 验证结果

已执行通过：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dmaven.resources.skip=true" "-Dtest=GraphvizServiceTest" test
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dmaven.resources.skip=true" -DskipTests compile

cd multi-agent-creator
npm run type-check
```

## 2026-05-20 阶段 8：RAG MVP 知识库增强

### 背景

本阶段对应 `document/plan.md` 的“阶段 8：RAG MVP”，目标是先完成可选个人知识库增强的最小闭环，而不是直接实现复杂 Agentic RAG。

当前实现重点：
- 用户可以创建个人知识库。
- 用户可以上传 `txt/md/pdf/docx` 文档。
- 系统保存知识库和文档元数据。
- 文档异步解析、切片、向量化并写入 pgvector。
- 创作时用户可以显式开启知识库增强。
- 规则服务决定是否检索知识库。
- 检索上下文注入标题、大纲、正文生成 prompt。
- RAG 失败时降级为普通创作，不中断文章生成。

明确未做：
- `RetrievalDecisionAgent`
- `QueryRewriteAgent`
- reranker
- 联网检索
- 跨用户知识库共享

### 已完成内容

后端能力：
1. 新增知识库元数据模型与服务：`KnowledgeBase`、`KnowledgeDocument`、`KnowledgeIngestionJob`。
2. 新增知识库接口：
   - `POST /knowledge-base/create`
   - `GET /knowledge-base/list`
   - `POST /knowledge-base/{knowledgeBaseId}/upload`
   - `GET /knowledge-base/{knowledgeBaseId}/documents`
3. 文档上传采用 COS 优先、本地文件系统兜底。
4. 文档解析支持：
   - `txt/md` 直接读取
   - `pdf` 使用 PDFBox
   - `docx` 使用 Apache POI
5. `DocumentChunker` 负责切片，并记录 `chunkIndex`、`contentHash`、`tokenCount`、metadata。
6. `RagEmbeddingService` 默认调用 DashScope-compatible embeddings endpoint，并提供本地 deterministic fallback 方便开发跑通链路。
7. `PgVectorKnowledgeRepository` 使用独立 PostgreSQL JDBC / `DriverManager` 写入和检索 pgvector，不干扰 MySQL + MyBatis-Flex 主数据源。
8. 新增 `RetrievalDecisionService`、`KnowledgeRetriever`、`RagContextBuilder`、`RagService`。
9. 文章生成链路接入 RAG：
   - `ArticleCreateRequest` 增加知识库增强字段
   - `Article` / `ArticleState` / `ArticleContext` / `ArticleVO` 同步字段
   - `ArticleAsyncService` 在标题、大纲、正文阶段构建 `retrievedContext`
   - `ArticleAgent` 将 `retrievedContext` 注入 prompt
   - `ArticleAgentOrchestrator` 保留并传递 `retrievedContext`

前端能力：
1. `ArticleCreatePage.vue` 增加知识库增强区域。
2. 支持开关“知识库增强”、勾选“使用我的写作风格记忆”、多选知识库、内联创建知识库、上传文档到指定知识库。
3. 新增 `multi-agent-creator/src/api/knowledgeBaseController.ts`。
4. 更新 `multi-agent-creator/src/api/typings.d.ts`。

数据库与配置：
1. 新增 `sql/add_rag_tables.sql`。
2. 更新 `sql/create_table.sql`。
3. 新增 RAG 配置：`rag.enabled`、`rag.top-k`、`rag.context-max-chars`、`rag.local-storage-dir`、`rag.datasource.*`、`rag.embedding.*`。
4. 新增 `docker-compose.rag.yml`，用于本地单独启动 pgvector。
5. 更新主 `docker-compose.yml`，增加 `pgvector` 服务，并让后端容器连接 `jdbc:postgresql://pgvector:5432/...`。

### 运行依赖说明

pgvector 本身不需要外部 API Key。它只是 PostgreSQL 的向量扩展。

需要外部 API Key 的是 embedding 服务：
- 生产环境建议配置 `spring.ai.dashscope.api-key`
- 当前 `RagEmbeddingService` 支持本地 fallback，但 fallback 只用于开发跑通链路，不具备真实语义检索质量

本地 RAG 需要启动 PostgreSQL + pgvector：

```powershell
docker compose -f docker-compose.rag.yml up -d pgvector
```

默认连接配置：

```yaml
rag:
  datasource:
    url: jdbc:postgresql://localhost:5432/multi_agent_rag
    username: postgres
    password: postgres
```

如果使用完整 Docker Compose 部署，后端不能连接 `localhost:5432`，应连接 Compose 服务名：

```yaml
RAG_DATASOURCE_URL: jdbc:postgresql://pgvector:5432/multi_agent_rag
```

### 运行中修复

#### 1. 知识库文件上传超过 1MB 失败

问题现象：

```text
MaxUploadSizeExceededException: Maximum upload size exceeded
FileSizeLimitExceededException: The field file exceeds its maximum permitted size of 1048576 bytes.
```

原因：
- Spring Boot 默认 multipart 单文件上限为 1MB。
- 错误发生在 multipart 解析阶段，尚未进入 `KnowledgeBaseController`。

修复：
1. 在 `application.yml` 增加 `spring.servlet.multipart.max-file-size=50MB` 和 `spring.servlet.multipart.max-request-size=60MB`。
2. 同步更新 `application-local.yml.example` 和 `application-prod.example`。
3. `GlobalExceptionHandler` 增加 `MaxUploadSizeExceededException` 处理，返回明确业务提示。
4. 前端上传前增加 50MB 文件大小检查。

#### 2. RAG 检索连接 pgvector 失败

问题现象：

```text
pgvector 检索失败: Connection to localhost:5432 refused
```

原因：
- 本地没有启动 PostgreSQL + pgvector。
- 原主 `docker-compose.yml` 没有 pgvector 服务。
- 容器部署时后端如果连接 `localhost:5432`，实际指向后端容器自身，而不是 pgvector 容器。

修复：
1. 新增独立 `docker-compose.rag.yml`，方便本地只启动 pgvector。
2. 主 `docker-compose.yml` 增加 `pgvector` 服务、健康检查和数据卷。
3. 后端 Docker 环境变量增加 `RAG_DATASOURCE_URL`、`RAG_DATASOURCE_USERNAME`、`RAG_DATASOURCE_PASSWORD`。
4. `RagService` 降级日志降噪：warn 只记录摘要，完整堆栈放到 debug。

### 验证结果

已执行通过：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dmaven.resources.skip=true" -DskipTests compile
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q "-Dmaven.resources.skip=true" "-Dtest=DocumentChunkerTest,RetrievalDecisionServiceTest,JsonStructuredOutputServiceTest" test

cd multi-agent-creator
npm run type-check
```

新增 pgvector Compose 校验通过：

```powershell
docker compose -f docker-compose.rag.yml config
```

主 Compose 配置在补充临时必需环境变量后校验通过：

```powershell
$env:DASHSCOPE_API_KEY='dummy'
$env:PEXELS_API_KEY='dummy'
docker compose config
```

说明：
- 真实启动 pgvector 时要求 Docker Desktop 正在运行。
- 当前机器当时 Docker Desktop 未运行，启动容器失败：`open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.`

### 当前状态

- RAG MVP 代码链路已完成。
- RAG 默认是可选增强，不会阻塞普通文章创作。
- 未开启知识库增强时不会触发检索。
- 开启知识库增强但 pgvector 不可用时，会降级为普通创作。
- 文件上传默认支持最大 50MB。
- 本地需要启动 `docker-compose.rag.yml` 中的 pgvector 后，才能验证真实向量写入和检索。

### 待确认与后续建议

1. 执行数据库迁移：
   - MySQL 执行 `sql/add_rag_tables.sql`
   - PostgreSQL 侧确保 `CREATE EXTENSION IF NOT EXISTS vector;`
2. 启动 pgvector 后重新上传测试文档，因为 pgvector 未启动期间上传的文档可能没有成功写入 chunk。
3. 使用真实 `spring.ai.dashscope.api-key` 跑一次端到端：
   - 创建知识库
   - 上传 `.md`
   - 确认文档解析状态完成
   - 创建文章并开启知识库增强
   - 检查生成内容是否体现检索材料
4. 后续可增强：
   - 前端展示文档解析状态
   - 增加知识库/文档删除或禁用接口
   - AgentLog metadata 记录 `ragSkipped`、`ragError`、`retrievedChunkCount`
   - 为 `RagService` 增加 mock 检索的单元测试
   - 用真实 DashScope embedding 响应确认 `RagEmbeddingService` 的响应解析字段

## 2026-05-21 阶段 10：AgentEval 量化评估

### 背景

项目决定先跳过 MCP 实现，基于当前已有架构和代码完成系统量化评估。阶段 10 的目标不是新增生成能力，而是用固定题集证明当前 Agent 闭环相对基础流程的收益，并输出可用于项目展示、简历和面试说明的 Markdown 报告。

本次评估明确不启用 RAG，也不接入 MCP，避免检索质量、知识库内容质量、工具协议标准化等额外变量干扰结论。

### baseline 与 experiment 定义

baseline：

- 不启用 RAG。
- 不启用 MCP。
- 使用基础 `ArticleAgentService` 链路。
- 自动模拟用户确认：标题默认选择第一个候选，大纲默认确认。
- ReviewAgent 主要用于记录标题、大纲、正文等评分，不作为完整闭环阻断逻辑。
- 图片计划如果 Review 未通过，作为弱 baseline 尽量保留已有图片需求继续执行；若没有可用图片需求则记录失败。

experiment：

- 不启用 RAG。
- 不启用 MCP。
- 使用当前 `ArticleAgentOrchestrator` 完整闭环。
- 启用 `JsonStructuredOutputService` 的解析、Schema 校验、repair、retry 指标记录。
- 启用 `ReviewAgent` 对标题、大纲、正文、配图计划、图片结果评分。
- 启用 `ImageAgent + ImageToolExecutor` 的单图评审、重规划和 fallback 链路。

### 已完成内容

1. 新增 AgentEval DTO：
   - `AgentEvalRequest`
   - `AgentEvalResponse`

2. 新增评估执行模块：
   - `AgentEvalService`
   - `AgentEvalMode`
   - `AgentEvalRunRecord`
   - `AgentEvalApplicationRunner`

3. 新增指标聚合模块：
   - `AgentEvalAggregator`
   - `AgentEvalSummary`

4. 新增 Markdown 报告生成器：
   - `AgentEvalReportGenerator`

5. 新增接口：
   - `POST /api/agent-eval/run`

6. 新增默认评估题集：
   - `src/main/resources/eval/topics.json`
   - 默认 20 个主题

7. 新增单元测试：
   - `AgentEvalAggregatorTest`
   - 覆盖指标聚合和报告结构，确保报告包含：
     - baseline vs experiment 差异表
     - 指标对比总表
     - 文本分析报告

8. `pom.xml` 增加：
   - `project.build.sourceEncoding=UTF-8`
   - 避免中文报告字符串在 Maven 编译时乱码。

### 评估指标

报告中会聚合以下指标：

- 稳定性：
  - 任务成功率
  - 阶段成功率
  - JSON 首次解析成功率
  - Schema 通过率
  - 平均 repair 次数
  - 平均 retry 次数
- 质量：
  - 标题评分
  - 大纲评分
  - 正文评分
  - 配图计划评分
  - 图片结果评分
  - 各阶段平均分、P50、P95、通过率
- 耗时：
  - 平均总耗时
  - P95 总耗时
  - 各 phase 平均耗时和 P95 耗时
- 工具：
  - 图片工具成功率
  - 图片 fallback 触发率
  - 平均图片重规划次数
- token：
  - 当前第一版优先输出 estimated tokens。
  - 估算规则为：中文字符数约按 `字符数 / 1.5`，其他字符约按 `字符数 / 4`，输入、输出和最终状态文本合并统计。

### 报告格式

每次运行会生成一份 Markdown 总报告，默认路径：

```text
document/eval/{evalRunId}.md
```

报告包含：

1. 评估摘要
2. baseline vs experiment 差异表
3. 指标对比总表
4. 分阶段指标表
5. 生成文章索引表
6. 失败与重试案例表
7. 文本分析报告
8. Token 消耗估算

### 文章产物导出

为了能查看每个测试过程中 baseline 和 experiment 实际生成的图文文章，AgentEval 会为每个样本导出独立 Markdown 文件：

```text
document/eval/{evalRunId}/articles/*.md
```

每篇文章文件包含：

- 分组：baseline / experiment
- taskId
- 原始主题
- 成功/失败状态
- Review 分数
- 大纲
- 图片 URL 和图片工具 method
- 最终图文 Markdown 正文

总报告中的“生成文章索引表”会列出每个主题对应的 baseline 和 experiment 文章路径，方便直接打开对比。

### 执行方式

推荐先用 1 个主题试跑：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8567/api/agent-eval/run" `
  -ContentType "application/json" `
  -Body '{"limit":1,"wordRange":"short","outputDir":"document/eval"}'
```

跑完整默认 20 个主题：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8567/api/agent-eval/run" `
  -ContentType "application/json" `
  -Body '{"limit":20,"wordRange":"short","outputDir":"document/eval"}'
```

也可以在应用启动时自动执行：

```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--agent-eval.enabled=true --agent-eval.limit=20"
```

### Token 预算

按完整链路、20 个主题、baseline + experiment 估算：

| 规模 | 预计消耗 |
| --- | --- |
| 单主题单版本 | 35k - 55k tokens |
| 单主题双版本 | 70k - 110k tokens |
| 20 主题双版本 | 1.4M - 2.2M tokens |
| 加 repair/retry/replan 浮动后 | 1.7M - 2.8M tokens |

### 验证结果

尝试执行：

```powershell
.\mvnw.cmd -Dtest=AgentEvalAggregatorTest test
```

结果：

- Maven Wrapper 已能下载并启动。
- 当前 shell 下 Maven 使用的是 Java 8 JRE，不满足项目 Java 21 要求。
- 编译阶段失败：

```text
No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?
Java version: 1.8.0_281
```

需要切换到 JDK 21 后重新执行测试。

建议命令：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -Dtest=AgentEvalAggregatorTest test
```

或使用本地已有 Maven：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -Dtest=AgentEvalAggregatorTest test
```

### 当前状态

- 阶段 10 AgentEval 核心代码已完成。
- 已支持固定题集运行 baseline/experiment。
- 已支持 Markdown 总报告。
- 已支持导出每次生成的 baseline/experiment 图文文章。
- 已支持失败样本不中断整批评估，失败进入案例表。
- 当前未在本机完成 Java 编译验证，原因是当前 shell 使用 Java 8 JRE，需要切换到 JDK 21 后执行测试。

### 后续建议

1. 切换到 JDK 21 后执行 `AgentEvalAggregatorTest`。
2. 使用 `limit=1` 先跑一轮真实端到端，确认报告与文章 Markdown 都能生成。
3. 检查 `document/eval/{evalRunId}/articles/` 下 baseline 和 experiment 图文文章是否符合预期。
4. 再执行 `limit=20` 完整评估。
5. 若真实模型响应 metadata 能拿到 token usage，后续可把 estimated tokens 升级为真实 usage 统计。
