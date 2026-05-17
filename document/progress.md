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
