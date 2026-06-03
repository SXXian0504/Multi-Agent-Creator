# 配图评审失败后的 Human-in-the-loop 修订需求与开发规划

## 1. 背景

当前配图链路已经具备：

- `ImageAgent` 根据大纲或正文生成配图规划。
- `ImageToolExecutionService` 调用图片工具生成图片，并在单图失败后自动重规划。
- `ReviewAgent.reviewImageResult(...)` 对图片结果进行多模态评审，输出 `ImageReviewResult`，包含 `observation` 和 `revisionAdvice`。
- `ArticleGenerationApplicationService.executePhase3(...)` 在正文、配图、合并完成后直接将文章状态置为 `COMPLETED`。

当前不足：

1. 图片评审失败后系统只会自动重规划、best-effort 或 fallback，用户无法针对当前图片输入修订意图。
2. 即使图片评审分数低，只要生成了候选图，流程仍可能进入合并并完成，缺少“待用户确认/修订”的停顿点。
3. 当前图生图能力没有被抽象为“基于原图 + 用户 prompt + 评审建议 + 原配图规划 prompt”的专用修订流程。
4. 前端文章详情页可以展示配图和评审日志，但没有针对失败配图的修订表单、预览、确认入口。

本次改进目标是：当配图评审失败且自动重规划仍未通过时，将任务停在可恢复的人工介入阶段。用户输入文本 prompt 后，系统调用图生图模型基于当前图片和上下文生成新图。用户认可后，替换对应配图、重新合并正文，并将文章状态恢复为完成态。

## 2. 目标与非目标

### 2.1 目标

1. 图片评审失败后进入 Human-in-the-loop 状态，而不是直接失败或静默 fallback。
2. 用户能看到失败图片、原配图规划、ReviewAgent 建议和当前使用的生成 prompt。
3. 用户能输入修订 prompt，系统组合以下上下文生成新图：
   - 当前图片；
   - 用户输入 prompt；
   - `ImageReviewResult.observation` / `revisionAdvice`；
   - 原 `ImageRequirement.prompt` / `keywords` / `reason`；
   - 文章标题、章节标题、平台、风格。
4. 图生图生成的新图需要再次经过 `ReviewAgent.reviewImageResult(...)`。
5. 用户确认新图后，系统替换原图片、重新合并 `fullContent`，并将 `status` / `phase` 更新回完成态。
6. 全链路保留可追踪记录，包括用户 prompt、源图 URL、新图 URL、评审结果、确认时间。

### 2.2 非目标

1. 第一版不做多张图的批量交互式修订；先支持单张图逐张修订。
2. 第一版不引入新的外部 MCP 协议；优先复用现有 image adapter/service 边界。
3. 第一版不重写整篇文章，也不重新规划所有配图；只修订指定 `position` / `placeholderId` 的图片。
4. 第一版不做复杂版本树；只保留修订历史列表和当前生效图片。

## 3. 用户故事

1. 作为用户，当文章生成完成前某张配图质量不佳时，我希望系统停下来告诉我是哪张图没通过评审，以及评审建议是什么。
2. 作为用户，我希望基于当前图输入“更偏扁平科技风、突出流程节点，不要真实人物”等自然语言要求，让系统重新生成配图。
3. 作为用户，我希望看到修订后的新图，并能选择“认可使用”或“继续修改”。
4. 作为用户，认可图片后，我希望文章变回已完成，并且正文中的对应配图已经被新图替换。
5. 作为开发者，我希望整个过程有清晰状态、接口和日志，后续可以扩展为多图批量修订或更换图生图模型。

## 4. 状态设计

### 4.1 任务状态

保留 `ArticleStatusEnum` 的任务级状态：

| 状态 | 含义 |
| --- | --- |
| `PROCESSING` | 后台生成或修订中 |
| `COMPLETED` | 文章和当前配图均已被用户或系统确认 |
| `FAILED` | 不可恢复失败 |

图片需要用户介入时，不建议使用 `FAILED`。推荐保持 `PROCESSING`，并通过 `ArticlePhaseEnum` 表示等待用户动作，避免列表页把文章归类为不可恢复失败。

### 4.2 新增阶段

在 `ArticlePhaseEnum` 中新增：

| 阶段 | 说明 |
| --- | --- |
| `IMAGE_WAITING_USER_REVISION` | 图片评审失败，等待用户输入修订 prompt |
| `IMAGE_REVISING` | 已收到用户 prompt，正在调用图生图模型修订 |
| `IMAGE_WAITING_USER_CONFIRM` | 新图已生成并通过基础评审，等待用户确认使用 |

状态流转建议：

```text
IMAGE_REVIEWING
  -> IMAGE_REPLANNING
  -> IMAGE_REVIEWING
  -> IMAGE_WAITING_USER_REVISION
  -> IMAGE_REVISING
  -> IMAGE_REVIEWING
  -> IMAGE_WAITING_USER_CONFIRM
  -> MERGING
  -> COMPLETED
```

异常流转：

```text
IMAGE_REVISING -> IMAGE_WAITING_USER_REVISION
IMAGE_WAITING_USER_CONFIRM -> IMAGE_REVISING
IMAGE_WAITING_USER_REVISION -> FAILED
```

`FAILED` 只用于用户取消、资源不可用、权限不足、任务数据损坏等不可恢复场景。

## 5. 数据模型

### 5.1 新增 DTO：ImageRevisionRequest

```java
public class ImageRevisionRequest {
    private String taskId;
    private Integer position;
    private String placeholderId;
    private String userPrompt;
}
```

校验规则：

- `taskId` 必填。
- `position` 和 `placeholderId` 至少一个必填；优先用 `placeholderId` 精确定位。
- `userPrompt` 必填，建议限制 10-1000 字。
- 仅文章所有者可提交。
- 当前阶段必须是 `IMAGE_WAITING_USER_REVISION` 或 `IMAGE_WAITING_USER_CONFIRM`。

### 5.2 新增 DTO：ImageRevisionConfirmRequest

```java
public class ImageRevisionConfirmRequest {
    private String taskId;
    private Integer position;
    private String placeholderId;
    private String revisionId;
    private Boolean approved;
    private String userPrompt;
}
```

说明：

- `approved=true`：使用当前候选修订图，重新合并并完成。
- `approved=false` 且 `userPrompt` 非空：继续基于最新候选图修订。

### 5.3 新增状态对象：ImageRevisionCandidate

建议先作为 JSON 字段持久化在 `Article` 上；如果后续要做复杂历史查询，再拆表。

```java
public class ImageRevisionCandidate {
    private String revisionId;
    private Integer position;
    private String placeholderId;
    private String sourceImageUrl;
    private String revisedImageUrl;
    private String method;
    private String userPrompt;
    private String composedPrompt;
    private ArticleState.ImageRequirement originalRequirement;
    private ArticleState.ImageResult originalImage;
    private ImageReviewResult previousReviewResult;
    private ImageReviewResult revisedReviewResult;
    private String status; // GENERATED / REVIEW_FAILED / USER_APPROVED / USER_REJECTED
    private Long createdAt;
    private Long confirmedAt;
}
```

### 5.4 Article 持久化字段

短期最小改动：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `pending_image_revisions` | JSON/TEXT | 当前待处理或历史修订候选 |

也可以复用现有 `imageExecutionTraces` 思路，但不建议只写 trace。原因是前端需要直接读取“当前待确认修订图”，trace 更适合作为审计日志。

`ArticleVO` 需要增加：

- `imageReviewResults`
- `imageExecutionTraces`
- `pendingImageRevisions`

前端详情页才能在不额外查日志的情况下展示修订上下文。

## 6. Prompt 合成策略

### 6.1 输入上下文

图生图修订 prompt 应由后端统一合成，不让前端拼接完整上下文。

输入包括：

- 文章主题：`topic`
- 主标题：`mainTitle`
- 章节标题：`sectionTitle`
- 当前图片 URL 或图片 bytes
- 原配图方式：`imageSource`
- 原配图 prompt / keywords / reason
- ReviewAgent 的 `observation`
- ReviewAgent 的 `revisionAdvice`
- 用户本轮 `userPrompt`
- 平台和写作 Skill 的配图偏好

### 6.2 合成模板

```text
你是文章配图修订助手。请基于输入图片生成一张替换图。

文章主题：{topic}
文章标题：{mainTitle}
配图位置：{position}
章节标题：{sectionTitle}
平台与风格：{platform} / {styleName}

原配图规划：
- 工具：{imageSource}
- 原 prompt：{originalPrompt}
- 原关键词：{keywords}
- 规划理由：{reason}

评审反馈：
- 观察：{observation}
- 修改建议：{revisionAdvice}

用户本轮要求：
{userPrompt}

生成要求：
1. 保留与文章章节的语义关联。
2. 优先解决评审反馈指出的问题。
3. 用户要求优先级高于原 prompt，但不能偏离文章主题。
4. 不要生成无关文字、水印、Logo、版权角色或难以阅读的小字。
5. 输出应适合作为文章配图，构图清晰，主体明确。
```

### 6.3 优先级

优先级从高到低：

1. 安全和合规约束。
2. 用户本轮 prompt。
3. ReviewAgent 的失败原因和修改建议。
4. 原配图规划 prompt / reason。
5. 写作 Skill 的配图偏好。

## 7. 图生图服务设计

### 7.1 新增接口

建议新增领域服务：

```java
public interface ImageRevisionService {
    ImageRevisionCandidate reviseImage(ImageRevisionCommand command);
    void confirmRevision(String taskId, String revisionId, User loginUser);
}
```

`ImageRevisionCommand` 包含文章、原图、原 requirement、上一轮 review、用户 prompt。

### 7.2 Adapter 能力

现有 `QwenImageService` 是文生图，`NanoBananaService` 也主要暴露 prompt 生成。需要新增“图生图”能力抽象：

```java
public interface ImageToImageService {
    ImageMethodEnum getMethod();
    ImageData reviseImage(ImageRevisionInput input);
    boolean isAvailable();
}
```

`ImageRevisionInput`：

```java
public class ImageRevisionInput {
    private String prompt;
    private String sourceImageUrl;
    private byte[] sourceImageBytes;
    private String sourceMimeType;
    private String aspectRatio;
}
```

第一版模型选择：

1. 优先使用支持图生图的模型 adapter。
2. 如果当前 `NanoBanana` SDK 支持图片输入，则扩展 `NanoBananaService`。
3. 如果当前供应商不支持图生图，则退化为文生图，但必须在 trace 中标记 `imageToImage=false`，避免误导。

## 8. 后端接口设计

### 8.1 获取待修订图片

```http
GET /article/{taskId}/image-revisions
```

返回：

```json
{
  "taskId": "xxx",
  "phase": "IMAGE_WAITING_USER_REVISION",
  "pending": [
    {
      "position": 2,
      "placeholderId": "{{IMAGE_PLACEHOLDER_1}}",
      "sourceImageUrl": "...",
      "sectionTitle": "xxx",
      "originalPrompt": "...",
      "observation": "...",
      "revisionAdvice": "...",
      "lastScore": 62
    }
  ]
}
```

### 8.2 提交用户修订 prompt

```http
POST /article/image-revisions/revise
```

请求：

```json
{
  "taskId": "xxx",
  "position": 2,
  "placeholderId": "{{IMAGE_PLACEHOLDER_1}}",
  "userPrompt": "改成扁平科技插画，突出流程节点，不要真实人物"
}
```

行为：

1. 校验权限与阶段。
2. 将阶段置为 `IMAGE_REVISING`，状态保持 `PROCESSING`。
3. 读取原图、原 requirement、上一轮 review。
4. 合成图生图 prompt。
5. 调用 `ImageRevisionService` 生成新图。
6. 调用 `ReviewAgent.reviewImageResult(...)` 复评。
7. 保存 `ImageRevisionCandidate`。
8. 阶段置为 `IMAGE_WAITING_USER_CONFIRM`。

### 8.3 用户确认或继续修改

```http
POST /article/image-revisions/confirm
```

请求：

```json
{
  "taskId": "xxx",
  "revisionId": "rev_xxx",
  "approved": true
}
```

`approved=true` 行为：

1. 替换 `images` 中对应图片。
2. 更新 `coverImage`，如果修订的是封面。
3. 重新执行 `ContentMergeService.mergeImagesIntoContent(...)`。
4. 保存 `fullContent`。
5. 标记 revision 为 `USER_APPROVED`。
6. 如果没有其他待修订图片，将 `status=COMPLETED`，`phase=COMPLETED`，`completedTime=now`。
7. 如果还有其他待修订图片，阶段回到 `IMAGE_WAITING_USER_REVISION`。

`approved=false` 行为：

- 如果带 `userPrompt`，继续走 `/revise` 等价逻辑。
- 如果不带 `userPrompt`，仅标记当前候选为 `USER_REJECTED`，阶段回到 `IMAGE_WAITING_USER_REVISION`。

## 9. 工作流改造

### 9.1 图片执行策略调整

当前 `ImageToolExecutionService.executeOne(...)` 在多轮失败后会选择 best image 或 fallback。新增策略：

```text
图片生成成功但评审不通过
  -> 自动重规划最多 N 次
  -> 仍不通过
  -> 如果 HITL 开启：记录待修订项，返回 PARTIAL_REQUIRES_USER_REVISION
  -> 如果 HITL 关闭：保持当前 best-effort/fallback 行为
```

建议新增配置：

```yaml
article:
  image:
    hitl-revision-enabled: true
    max-auto-replan-attempts: 2
    max-user-revision-attempts: 5
```

### 9.2 Phase3 完成逻辑调整

`ArticleGenerationApplicationService.executePhase3(...)` 保存内容后不要无条件完成。

伪逻辑：

```java
articleService.saveArticleContent(taskId, state);

if (state.hasPendingImageRevisions()) {
    articleService.savePendingImageRevisions(taskId, state.getPendingImageRevisions());
    articleService.updateArticleStatus(taskId, PROCESSING, null);
    articleService.updatePhase(taskId, IMAGE_WAITING_USER_REVISION);
    publishSseMessage(taskId, IMAGE_REVISION_REQUIRED, payload);
    return;
}

articleService.updateArticleStatus(taskId, COMPLETED, null);
articleService.updatePhase(taskId, COMPLETED);
publish ALL_COMPLETE;
```

### 9.3 SSE 事件

新增 `SseMessageTypeEnum`：

| 类型 | 说明 |
| --- | --- |
| `IMAGE_REVISION_REQUIRED` | 图片需要用户修订 |
| `IMAGE_REVISION_STARTED` | 已开始图生图修订 |
| `IMAGE_REVISION_COMPLETE` | 修订图生成完成，等待用户确认 |
| `IMAGE_REVISION_ACCEPTED` | 用户确认修订图 |

前端创建页和详情页都应能处理这些事件；详情页刷新也应能从 `ArticleVO` 恢复当前待处理状态。

## 10. 前端交互设计

### 10.1 文章详情页

当 `phase=IMAGE_WAITING_USER_REVISION` 或 `IMAGE_WAITING_USER_CONFIRM` 时展示“配图需要确认”区域。

每个待修订项展示：

- 当前图预览；
- 章节标题 / 图片位置；
- 评审分数；
- 评审观察；
- 修改建议；
- 原 prompt / keywords，可折叠；
- 用户 prompt 输入框；
- “生成修订图”按钮。

生成新图后展示：

- 原图和新图并排对比；
- 新图评审分数和建议；
- “认可使用”；
- “继续修改”。

### 10.2 创建页 SSE

如果生成过程中收到 `IMAGE_REVISION_REQUIRED`：

- 停止展示“失败”态。
- 进入“等待你修订配图”态。
- 提供跳转详情页或内嵌修订面板。

### 10.3 列表页状态

新增 phase 文案：

- `IMAGE_WAITING_USER_REVISION`：等待修订配图
- `IMAGE_REVISING`：修订配图中
- `IMAGE_WAITING_USER_CONFIRM`：等待确认配图

`status=PROCESSING` 且这些 phase 时，不显示为普通“生成中”，应突出需要用户操作。

## 11. 权限、额度与成本

1. 修订接口必须校验文章所有者。
2. 用户每次提交图生图修订都应消耗图片生成额度或单独的“图片修订额度”。
3. `max-user-revision-attempts` 防止无限循环。
4. 图生图失败不应直接扣除完整额度；具体扣费策略可与现有图片生成额度保持一致。
5. 普通用户如果没有 AI 生图权限，应不能进入图生图修订；可提示升级或接受当前 best-effort 图。

## 12. 可观测性

需要记录：

- `AgentLog`：`image_revision_prompt_compose`
- `AgentLog`：`image_to_image_generate`
- `AgentLog`：`review_image_revision_result`
- `ImageExecutionTrace.finalStatus` 新增：
  - `USER_REVISION_REQUIRED`
  - `USER_REVISION_GENERATED`
  - `USER_REVISION_APPROVED`
  - `USER_REVISION_REJECTED`

指标：

- 图片自动评审失败率；
- HITL 触发率；
- 用户平均修订轮次；
- 修订后评审通过率；
- 用户确认率；
- 修订平均耗时；
- 图生图失败率。

## 13. 测试计划

### 13.1 单元测试

1. `ArticlePhaseEnum.canTransitionTo(...)`
   - `IMAGE_REVIEWING -> IMAGE_WAITING_USER_REVISION`
   - `IMAGE_WAITING_USER_REVISION -> IMAGE_REVISING`
   - `IMAGE_REVISING -> IMAGE_WAITING_USER_CONFIRM`
   - `IMAGE_WAITING_USER_CONFIRM -> MERGING`
   - `MERGING -> COMPLETED`

2. `ImageRevisionPromptBuilder`
   - 用户 prompt、review advice、原 requirement 均被纳入。
   - 空字段不会生成 `null` 字符串。
   - 用户 prompt 优先级说明存在。

3. `ImageRevisionService`
   - 成功生成 candidate。
   - 图生图不可用时按配置降级或失败。
   - 超过最大用户修订次数时拒绝。

4. `ContentMergeService`
   - 替换指定图片后重新合并。
   - 封面图和章节图都能正确替换。

### 13.2 集成测试

1. 模拟图片评审连续不通过，任务进入 `IMAGE_WAITING_USER_REVISION`，不进入 `FAILED`。
2. 提交用户 prompt 后生成修订候选，任务进入 `IMAGE_WAITING_USER_CONFIRM`。
3. 用户确认后文章进入 `COMPLETED`，`fullContent` 中图片 URL 已替换。
4. 用户拒绝后可继续修改。
5. 多张图片只有一张待修订时，确认后能完成；多张待修订时确认一张后仍等待下一张。

### 13.3 前端测试

1. 详情页能展示待修订图片和评审建议。
2. 输入 prompt 后按钮 loading，完成后显示新旧图对比。
3. 确认后状态刷新为已完成。
4. SSE 收到 `IMAGE_REVISION_REQUIRED` 时不显示失败弹窗。

## 14. 开发拆分

### 阶段 1：状态与数据契约

1. 扩展 `ArticlePhaseEnum`。
2. 扩展 `SseMessageTypeEnum`。
3. 新增 `ImageRevisionRequest`、`ImageRevisionConfirmRequest`、`ImageRevisionCandidate`。
4. 扩展 `ArticleVO`。
5. 新增数据库字段 `pending_image_revisions`，并补充 SQL 迁移。

交付标准：

- 后端能保存和返回待修订图片状态。
- 前端能根据 phase 显示基础状态文案。

### 阶段 2：工作流暂停点

1. 修改 `ImageToolExecutionService`，在自动重规划耗尽后生成待修订项。
2. 修改 `ImageExecutionResult`，增加 `pendingImageRevisions` 或类似字段。
3. 修改 `ArticleGenerationApplicationService.executePhase3(...)`，存在待修订项时停在 `IMAGE_WAITING_USER_REVISION`。
4. 发布 `IMAGE_REVISION_REQUIRED` SSE。

交付标准：

- 图片评审失败后任务不再直接失败或静默完成。
- 文章详情可看到待修订项。

### 阶段 3：图生图修订服务

1. 新增 `ImageRevisionPromptBuilder`。
2. 新增 `ImageToImageService` 抽象。
3. 扩展一个可用 adapter 支持图生图。
4. 新增 `ImageRevisionService.reviseImage(...)`。
5. 修订结果复用 `ReviewAgent.reviewImageResult(...)` 复评。

交付标准：

- 用户 prompt 能生成新图候选。
- 新图候选带评审结果和完整 trace。

### 阶段 4：确认与重新合并

1. 新增 `/article/image-revisions/revise`。
2. 新增 `/article/image-revisions/confirm`。
3. 实现替换 `images` 中指定图片。
4. 调用 `ContentMergeService` 重新生成 `fullContent`。
5. 没有剩余待修订项时恢复 `COMPLETED`。

交付标准：

- 用户确认后文章正文中的图片已替换。
- 状态回到 `COMPLETED`。

### 阶段 5：前端体验

1. 详情页新增配图修订面板。
2. 创建页处理新增 SSE。
3. 列表页增加待用户操作状态文案。
4. 新旧图对比、继续修改、确认使用。

交付标准：

- 用户能完整走通“看到失败原因 -> 输入 prompt -> 生成新图 -> 确认完成”。

### 阶段 6：测试与评估

1. 补齐单元测试和集成测试。
2. 增加 AgentEval 指标：HITL 触发率、修订通过率、用户确认轮次。
3. 编写回归用例：低相关真实图、图表渲染失败、AI 图风格不符。

交付标准：

- 自动测试覆盖状态流转、接口、合并逻辑。
- 至少 3 个失败配图案例可稳定进入 HITL 并完成修订。

## 15. 风险与决策点

1. 图生图模型能力不确定：需要先确认当前可用供应商是否支持图片输入；否则第一版只能退化为文生图修订。
2. 当前 `ArticlePhaseEnum` 源文件存在编码显示异常，修改前建议统一确认文件编码，避免提交扩大化 diff。
3. 并行正文和配图流程下，图片分支先失败等待用户时，正文可能已完成但尚未最终合并；需要确保保存 `content`、`imageRequirements`、`images` 和待修订项，便于恢复。
4. 如果多张图片同时待修订，前端第一版应明确一次处理一张，避免复杂状态同步。
5. `COMPLETED` 后用户再次修改图片属于“已完成文章的再编辑”能力，本次可以不做；先只覆盖生成流程中的评审失败修订。

## 16. 推荐 MVP 范围

第一版建议只做以下闭环：

1. 自动重规划仍失败后，进入 `IMAGE_WAITING_USER_REVISION`。
2. 只支持单张待修订图片。
3. 用户输入 prompt 后调用一个图生图 adapter。
4. 新图生成后进入 `IMAGE_WAITING_USER_CONFIRM`。
5. 用户确认后替换图片、重新合并、置为 `COMPLETED`。
6. 保留完整 revision JSON 和 trace。

这能最小化对现有工作流的冲击，同时把用户真正需要的“看问题、提要求、改图、确认完成”跑通。
