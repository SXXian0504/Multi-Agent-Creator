# 项目优化进度日志

## 2026-05-17

### 工作阶段

本轮工作基于：

- `document/plan.md`
- `document/stage0_baseline.md`

阶段 0 已完成，本轮继续执行：

1. 阶段 1：阶段状态和日志。
2. 阶段 2：ArticleAgent 阶段化。

遵循最小 MVP 原则：不重写主业务流程、不新增前端交互、不引入新依赖，优先复用现有代码。

### 阶段 1：阶段状态和日志

#### 工作内容

1. 扩展文章阶段状态。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/model/enums/ArticlePhaseEnum.java`
   - 补齐计划中的阶段枚举：
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
   - 保留旧状态 `TITLE_SELECTING`、`OUTLINE_EDITING` 作为兼容状态。

2. 扩展 Agent 日志字段。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/model/entity/AgentLog.java`
     - `src/main/java/com/sxxian/multiagentcreator/model/vo/AgentExecutionStats.java`
     - `src/main/java/com/sxxian/multiagentcreator/service/impl/AgentLogServiceImpl.java`
   - 新增或接入：
     - `traceId`
     - `phase`
     - `retryCount`
     - `metadata`
     - `phaseDurations`
   - 阶段 1 的 `traceId` 暂时复用 `taskId`，后续可以独立生成。

3. 扩展 AOP 注解和切面。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/annotation/AgentExecution.java`
     - `src/main/java/com/sxxian/multiagentcreator/aop/AgentExecutionAspect.java`
   - `@AgentExecution` 增加：
     - `phase`
     - `retryCount`
   - AOP 写入：
     - `traceId`
     - `phase`
     - `retryCount`
     - `metadata`
   - `metadata` 当前记录：
     - `description`
     - `className`
     - `methodName`
     - `retryCount`
     - `logVersion=stage1`

4. 给现有 Agent 方法补阶段标识。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/service/ArticleAgentService.java`
   - 已标注：
     - 标题生成：`TITLE_GENERATING`
     - 大纲生成：`OUTLINE_GENERATING`
     - 正文生成：`CONTENT_GENERATING`
     - 配图规划：`IMAGE_PLANNING`
     - 图片执行：`IMAGE_EXECUTING`
     - 图文合成：`MERGING`

5. 补充异步流程中的阶段更新。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/service/ArticleAsyncService.java`
   - 关键状态推进：
     - 标题生成完成后进入 `TITLE_WAITING_USER_CONFIRM`
     - 大纲生成前进入 `OUTLINE_GENERATING`
     - 大纲生成完成后进入 `OUTLINE_WAITING_USER_CONFIRM`
     - 正文生成前进入 `CONTENT_GENERATING`
     - 正文生成完成后进入 `IMAGE_PLANNING`
     - 配图规划完成后进入 `IMAGE_EXECUTING`
     - 图片执行完成后进入 `MERGING`
     - 完成后进入 `COMPLETED`
     - 异常后进入 `FAILED`

6. 兼容旧确认接口阶段判断。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/service/impl/ArticleServiceImpl.java`
   - 标题确认允许：
     - `TITLE_WAITING_USER_CONFIRM`
     - `TITLE_SELECTING`
   - 大纲确认和 AI 修改大纲允许：
     - `OUTLINE_WAITING_USER_CONFIRM`
     - `OUTLINE_EDITING`

7. 增加 SQL 迁移。
   - 文件：`sql/add_agent_log_trace_fields.sql`
   - 为 `agent_log` 增加：
     - `traceId`
     - `phase`
     - `retryCount`
     - `metadata`
     - `idx_traceId`
     - `idx_phase`
   - 同步更新了 `sql/create_table.sql` 的 `agent_log` 初始表结构。

#### 交付结果

阶段 1 已完成最小闭环：

- 任务可以进入更细粒度阶段状态。
- AOP 日志具备 `traceId`、`phase`、`retryCount`、`metadata`。
- `/article/execution-logs/{taskId}` 返回的统计结果可包含 `traceId` 和阶段耗时聚合。
- 保留旧阶段名兼容，避免破坏当前前端确认流程。

### 阶段 2：ArticleAgent 阶段化

#### 工作内容

1. 新增统一文本 Agent。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/agent/ArticleAgent.java`
   - 提供阶段方法：
     - `generateTitles`
     - `generateOutline`
     - `generateContent`
   - 提供 `rerunCurrentPhase`，用于后续用户反馈后只重跑当前阶段。
   - 当前只收敛标题、大纲、正文；图片规划、图片执行、图文合成仍沿用现有链路。

2. 新增文章上下文 DTO。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/model/dto/article/ArticleContext.java`
   - 用于统一承载文本生成阶段上下文：
     - `taskId`
     - `topic`
     - `userDescription`
     - `style`
     - `phase`
     - `titleOptions`
     - `title`
     - `outline`
     - `content`
     - `feedbackHistory`
   - 提供：
     - `fromState`
     - `applyToState`
     - `getLatestFeedbackContent`

3. 新增用户反馈 DTO。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/model/dto/article/UserFeedback.java`
   - 当前只作为服务层能力预留，不新增数据库表。

4. 旧服务路径接入统一 ArticleAgent。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/service/ArticleAgentService.java`
   - `executePhase1_GenerateTitles` 改为调用 `ArticleAgent.generateTitles`
   - `executePhase2_GenerateOutline` 改为调用 `ArticleAgent.generateOutline`
   - `executePhase3_GenerateContent` 的正文阶段改为调用 `ArticleAgent.generateContent`
   - `agent4AnalyzeImageRequirements`、`agent5GenerateImages`、`mergeImagesIntoContent` 继续保留。

5. StateGraph 编排路径接入统一 ArticleAgent。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/agent/ArticleAgentOrchestrator.java`
   - 原节点：
     - `TitleGeneratorAgent`
     - `OutlineGeneratorAgent`
     - `ContentGeneratorAgent`
   - 已替换为：
     - `article_generate_titles`
     - `article_generate_outline`
     - `article_generate_content`
   - `ImageAnalyzerAgent`、`ParallelImageGenerator`、`ContentMergerAgent` 保持原样。

6. AOP 支持新上下文。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/aop/AgentExecutionAspect.java`
   - 支持从 `ArticleContext` 和 `OverAllState` 中提取 `taskId` 和输入摘要。

#### 交付结果

阶段 2 已完成最小 MVP：

- 标题、大纲、正文已收敛到统一 `ArticleAgent`。
- 仍保留清晰阶段方法边界。
- 旧 `ArticleAgentService` 流程和 StateGraph 编排流程均接入统一文本 Agent。
- 已预留用户反馈和当前阶段重跑能力，但没有新增外部接口，避免扩大改造范围。
- 原有三个独立节点类暂未删除，作为兼容和回滚参考保留。

### 验证结果

执行过编译验证：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q -DskipTests compile
```

结果：编译通过。

注意：

- 默认系统 Java 指向 JDK 8，不满足项目 `java.version=21`。
- 编译时需要显式使用 `C:\Users\sxxia\.jdks\ms-21.0.10`。

### 后续工程师接手建议

1. 阶段 3 开始前，优先检查数据库是否已执行：
   - `sql/add_phase_fields.sql`
   - `sql/add_agent_log_trace_fields.sql`

2. 如果要继续阶段 2 的“反馈重跑接口”，建议只新增服务方法或小接口：
   - 标题反馈只重跑 `ArticleAgent.generateTitles`
   - 大纲反馈只重跑 `ArticleAgent.generateOutline`
   - 正文反馈只重跑 `ArticleAgent.generateContent`
   - 不要重跑前置阶段。

3. 阶段 3 的 `JsonStructuredOutputService` 应优先接入 `ArticleAgent` 内部的三处 JSON 解析：
   - 标题候选解析。
   - 大纲解析。
   - 后续图片计划解析。

4. 当前 `metadata` 只是阶段 1 基础结构，后续应逐步写入：
   - JSON parse 成功/失败。
   - repair 次数。
   - retry 次数。
   - 工具调用结果。
   - fallback 信息。

5. 工作区内存在一些本轮任务无关的未提交改动和未跟踪文件，不应随意回退。

## 2026-05-17 阶段 3：JsonStructuredOutputService

### 工作内容

1. 新增统一结构化输出服务。
   - 文件：`src/main/java/com/sxxian/multiagentcreator/service/JsonStructuredOutputService.java`
   - 支持：
     - Markdown 代码块 JSON 提取。
     - 前后混杂解释文本时提取第一个完整 JSON 对象或数组。
     - Gson 强类型解析。
     - 根类型校验。
     - 业务规则校验。
     - 本地修复指标记录。
     - 固定次数重试原 Agent。

2. 新增结构化输出类型、指标和异常。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/model/enums/StructuredOutputTypeEnum.java`
     - `src/main/java/com/sxxian/multiagentcreator/model/dto/structured/StructuredOutputMetrics.java`
     - `src/main/java/com/sxxian/multiagentcreator/exception/StructuredOutputException.java`
   - 当前类型包括：
     - `TITLE_OPTIONS`
     - `OUTLINE_RESULT`
     - `IMAGE_PLAN`
     - `REVIEW_RESULT`
     - `IMAGE_REVIEW_RESULT`

3. 新增 JSON Schema 文件。
   - 目录：`src/main/resources/schemas/`
   - 文件：
     - `title_options.schema.json`
     - `outline_result.schema.json`
     - `image_plan.schema.json`
     - `review_result.schema.json`
     - `image_review_result.schema.json`
   - 阶段 3 MVP 不新增第三方 JSON Schema 校验依赖，Schema 文件先作为结构契约沉淀；运行时用根类型和业务校验器完成最小闭环。

4. 新增结构化输出指标上下文，并接入 AOP metadata。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/context/StructuredOutputTraceContext.java`
     - `src/main/java/com/sxxian/multiagentcreator/aop/AgentExecutionAspect.java`
   - `AgentLog.metadata` 中新增：
     - `structuredOutputMetrics`
     - `logVersion=stage3`

5. 接入现有解析路径。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/agent/ArticleAgent.java`
     - `src/main/java/com/sxxian/multiagentcreator/service/ArticleAgentService.java`
     - `src/main/java/com/sxxian/multiagentcreator/agent/agents/ImageAnalyzerAgent.java`
   - 已替换：
     - 标题候选解析。
     - 大纲解析。
     - AI 修改大纲解析。
     - 配图计划解析。
   - 旧服务路径和 StateGraph 路径都已接入。

6. 新增单元测试。
   - 文件：`src/test/java/com/sxxian/multiagentcreator/service/JsonStructuredOutputServiceTest.java`
   - 覆盖：
     - Markdown 代码块包裹 JSON。
     - JSON 前后有解释文本。
     - 字段缺失被拦截。
     - `imageSource` 枚举错误被拦截。
     - 业务校验失败后有限重试。

### 验证结果

执行过：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q -Dtest=JsonStructuredOutputServiceTest test
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q -DskipTests compile
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q test
```

结果：

- `JsonStructuredOutputServiceTest` 通过。
- 编译通过。
- 全量测试通过。

### 后续建议

1. 阶段 4 接入 `ReviewAgent` 时，可以直接复用 `REVIEW_RESULT` 和 `IMAGE_REVIEW_RESULT` 类型。
2. 如果后续需要严格 Draft JSON Schema 校验，再引入 JSON Schema 校验依赖；当前阶段为了控制 MVP 范围没有新增依赖。
3. 阶段 5 扩展 `ImagePlan` 时，再补充 `reason`、`retryCount` 等字段并同步更新 `image_plan.schema.json`。

## 2026-05-17 阶段 4：ReviewAgent 验收修复与前端补齐

### 工作背景

本阶段基于阶段 4 验收反馈继续修复 ReviewAgent 闭环，重点解决：

1. `marketing` 风格前端无法选择。
2. ReviewAgent 评分固定、建议未真正回流给 ArticleAgent。
3. 正文生成字数过长，缺少用户可控的字数范围。
4. ReviewAgent 接入高阶模型后触发 DashScope `url error`。
5. ReviewAgent 返回 JSON 包装形态不稳定，导致 `review_result根节点类型不匹配`。

### 工作内容

1. 前端创作页补齐商品营销风格。
   - 文件：
     - `multi-agent-creator/src/pages/article/ArticleCreatePage.vue`
     - `multi-agent-creator/src/constants/article.ts`
   - 结果：
     - 创作页可选择“商品营销”。
     - 创建请求可正常传递 `marketing` 风格。

2. 新增字数范围选择。
   - 文件：
     - `multi-agent-creator/src/pages/article/ArticleCreatePage.vue`
     - `multi-agent-creator/src/api/typings.d.ts`
     - `src/main/java/com/sxxian/multiagentcreator/model/dto/article/ArticleCreateRequest.java`
     - `src/main/java/com/sxxian/multiagentcreator/model/entity/Article.java`
     - `src/main/java/com/sxxian/multiagentcreator/model/vo/ArticleVO.java`
     - `src/main/java/com/sxxian/multiagentcreator/model/dto/article/ArticleState.java`
     - `src/main/java/com/sxxian/multiagentcreator/model/dto/article/ArticleContext.java`
   - 支持选项：
     - 自动评估
     - 短文 200-500 字
     - 中篇 800-1500 字
     - 长文 2000-3500 字
   - 后端新增 `wordRange` 字段和校验，并补充 SQL：
     - `sql/add_article_word_range.sql`
     - `sql/create_table.sql`

3. 去除正文 System Prompt 中固定字数限制。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/constant/PromptConstant.java`
     - `src/main/java/com/sxxian/multiagentcreator/agent/ArticleAgent.java`
   - 结果：
     - 不再强制“2000字左右”或每章固定字数。
     - 用户未选择字数范围时，由 ArticleAgent 根据主题和风格自行评估篇幅。
     - 营销类内容可自然生成更短的转化型图文。

4. ReviewAgent 评审建议回流 ArticleAgent。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/agent/ReviewAgent.java`
     - `src/main/java/com/sxxian/multiagentcreator/agent/ArticleAgent.java`
   - 结果：
     - 标题、大纲、正文低于 80 分时，ReviewAgent 生成修订建议。
     - ArticleAgent 将 `problems + suggestions + nextAction` 注入当前阶段重写 Prompt。
     - 自动重跑仅限当前阶段，最多 1 次。
     - 正文二次低分时保留重写结果和评审建议，不再直接阻断整个任务进入失败。

5. ReviewAgent 模型配置拆分。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/agent/ReviewAgent.java`
     - `src/main/resources/application.yml`
     - `src/main/resources/application-local.yml.example`
   - 修复问题：
     - 将纯文本评审也切到 `qwen-vl-plus-latest` 后，标题评审阶段可能触发 DashScope `url error`。
   - 结果：
     - 文本评审使用 `article.review.text-model`，默认 `qwen-max`。
     - 图片结果评审使用 `article.review.image-model`，默认 `qwen-vl-plus-latest`。
     - 仅当图片 URL 是合法 `http/https` 时才作为多模态 media 传入，否则退化为基于图片元数据的文本评审。

6. JsonStructuredOutputService 增强 ReviewResult 容错。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/service/JsonStructuredOutputService.java`
     - `src/test/java/com/sxxian/multiagentcreator/service/JsonStructuredOutputServiceTest.java`
   - 修复问题：
     - ReviewAgent 偶发返回单元素数组或命名包装对象，导致 `review_result根节点类型不匹配`。
   - 兼容形态：
     - `[ { ...ReviewResult } ]`
     - `{ "review_result": { ...ReviewResult } }`
   - 结果：
     - 解包后仍走原有根类型校验和业务字段校验。
     - 新增单元测试覆盖单元素数组包装和 `review_result` 命名包装。

### 验证结果

执行过：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q -Dtest=JsonStructuredOutputServiceTest test
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q -DskipTests compile
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q test
cd multi-agent-creator
npm run build
```

结果：

- `JsonStructuredOutputServiceTest` 通过。
- 后端编译通过。
- 后端全量测试通过。
- 前端构建通过，仍存在 Vite 大 chunk 警告，属于既有构建提示。

### 注意事项

1. 需要在目标数据库执行：
   - `sql/add_article_word_range.sql`
2. 本地运行后端时需要使用 JDK 21：
   - `C:\Users\sxxia\.jdks\ms-21.0.10`
3. 如果 DashScope 账号不可用 `qwen-max` 或 `qwen-vl-plus-latest`，可通过配置替换：
   - `article.review.text-model`
   - `article.review.image-model`
4. 阶段 4 当前只记录图片结果评审的观察和修订建议，图片重规划闭环仍留到阶段 5。

## 2026-05-17 阶段 4 后续问题修复

### 工作背景

阶段 4 验收后继续发现以下问题：

1. 用户选择短文或营销文案时，大纲仍容易生成固定 5 个章节，导致正文篇幅失控。
2. `ReviewAgent` 文本评审容易固定输出 85 分，缺少基于实际标题、大纲、正文内容的差异化判断。
3. 图片结果评审使用 `qwen-vl-plus-latest` 直接读取 COS URL 时，DashScope 可能返回 `HTTP 400 InvalidParameter: url error`。
4. 需要明确图片结果评审失败后是否会触发重新配图。

### 工作内容

1. 大纲生成接入字数范围和风格约束。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/constant/PromptConstant.java`
     - `src/main/java/com/sxxian/multiagentcreator/utils/ArticlePromptUtils.java`
     - `src/main/java/com/sxxian/multiagentcreator/agent/ArticleAgent.java`
     - `src/main/java/com/sxxian/multiagentcreator/service/ArticleAgentService.java`
     - `src/main/java/com/sxxian/multiagentcreator/agent/agents/OutlineGeneratorAgent.java`
   - 结果：
     - 去掉大纲 Prompt 中“核心观点 3-5 个”和“适合 2000 字左右文章”的固定暗示。
     - 新增大纲专用字数约束：
       - `short`：建议 2-3 章，每章 1-2 个要点，明确禁止默认生成 5 章。
       - `medium`：建议 4-5 章，每章 2-3 个要点。
       - `long`：建议 5-7 章，每章 2-4 个要点。
       - 未选择字数且为 `marketing` 风格时，默认采用 2-4 章的短转化结构。
     - 主 `ArticleAgent` 路径、旧 `ArticleAgentService` 路径和旧 `OutlineGeneratorAgent` 节点均接入同一套约束。

2. 强化 `ReviewAgent` 文本评审。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/agent/ReviewAgent.java`
     - `src/main/java/com/sxxian/multiagentcreator/constant/PromptConstant.java`
     - `src/main/java/com/sxxian/multiagentcreator/service/JsonStructuredOutputService.java`
     - `src/test/java/com/sxxian/multiagentcreator/service/JsonStructuredOutputServiceTest.java`
   - 当前实际模型：
     - 文本评审：`article.review.text-model`，默认 `qwen-max`。
     - 图片评审：`article.review.image-model`，默认 `qwen-vl-plus-latest`。
   - 结果：
     - `ReviewAgent` 在文本评审 Prompt 中加入内容结构画像，包括字数范围、标题候选数量、大纲章节数、正文二级标题数量、内容开头片段等。
     - 评审 Prompt 增加分数档位，明确禁止把 85 当作默认分。
     - 要求 `problems` 和 `suggestions` 引用或复述具体标题、章节、段落、卖点、论据或表达问题。
     - 文本评审温度调整为 `0.0`，减少随机泛化打分。
     - `JsonStructuredOutputService` 增加文本评审结果校验：
       - `dimensionScores.commonBaseline + styleFit + stageFit` 必须等于 `score`。
       - `score < 95` 时 `problems` 和 `suggestions` 不能为空。
     - 日志会打印实际调用模型和 `mediaCount`，便于确认运行时路径。

3. 修复图片结果评审 `url error`。
   - 文件：
     - `src/main/java/com/sxxian/multiagentcreator/agent/ReviewAgent.java`
   - 问题原因：
     - 之前只判断图片 URL 是否为 `http/https`，随后直接作为多模态 `Media` URL 传给 DashScope。
     - 即使 URL 语法合法，DashScope 仍可能因为无法访问 COS URL、签名/回源限制、网络或 Content-Type 问题返回 `url error`。
   - 结果：
     - 图片评审不再直接把 URL 交给 DashScope 拉取。
     - 后端先下载图片字节，再用 `ByteArrayResource` 作为多模态输入传给 `qwen-vl-plus-latest`。
     - 下载失败、URL 非 HTTP、图片为空或超过 10MB 时，直接降级为 `qwen-max` 纯文本评审。
     - 如果视觉模型调用仍返回 `url error` / `InvalidParameter` / `please check url`，会捕获并降级为纯文本评审，避免中断任务。

4. 明确当前图片评审闭环边界。
   - 当前行为：
     - 图片生成后会调用 `ReviewAgent.reviewImageResult(...)`。
     - 评审结果写入 `imageReviewResults`。
     - 即使单张图片评审失败，目前也不会自动触发重新配图分析、重新选择工具或重新生成图片。
   - 后续阶段 5 建议：
     - 当 `ImageReviewResult.approved=false` 且 `nextAction=REPLAN` 时，基于 `observation` / `revisionAdvice` 重写该张图片的 `prompt`、`keywords` 或 `imageSource`。
     - 单张图片最多重试 1-2 次。
     - 仍失败时保留最好结果或 fallback，并把评审问题展示给用户。

### 验证结果

执行过：

```powershell
$env:JAVA_HOME='C:\Users\sxxia\.jdks\ms-21.0.10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q -Dtest=JsonStructuredOutputServiceTest test
C:\Users\sxxia\.m2\wrapper\dists\apache-maven-3.9.14\db91789b\bin\mvn.cmd -q -DskipTests compile
```

结果：

- `JsonStructuredOutputServiceTest` 通过。
- 后端编译通过。

### 注意事项

1. 当前图片重规划闭环尚未实现，图片评审失败不会自动重新配图。
2. 图片评审现在优先使用本地下载后的图片字节做多模态输入；如果部署环境无法访问图片 URL，会自动退化为纯文本评审。
3. `ReviewAgent` 的实际模型可通过以下配置替换：
   - `article.review.text-model`
   - `article.review.image-model`
