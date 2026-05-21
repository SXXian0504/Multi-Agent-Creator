package com.sxxian.multiagentcreator.article.workflow.legacy;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.google.gson.reflect.TypeToken;
import com.sxxian.multiagentcreator.article.agent.ArticleAgent;
import com.sxxian.multiagentcreator.image.planning.ImageAgent;
import com.sxxian.multiagentcreator.image.execution.ImageToolExecutionService;
import com.sxxian.multiagentcreator.article.review.ReviewAgent;
import com.sxxian.multiagentcreator.article.workflow.ContentMergeService;
import com.sxxian.multiagentcreator.annotation.AgentExecution;
import com.sxxian.multiagentcreator.constant.PromptConstant;
import com.sxxian.multiagentcreator.exception.ReviewRejectedException;
import com.sxxian.multiagentcreator.model.dto.article.ArticleContext;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.image.ImageExecutionResult;
import com.sxxian.multiagentcreator.model.dto.image.ImageRequest;
import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import com.sxxian.multiagentcreator.model.enums.ImageMethodEnum;
import com.sxxian.multiagentcreator.model.enums.SseMessageTypeEnum;
import com.sxxian.multiagentcreator.model.enums.StructuredOutputTypeEnum;
import com.sxxian.multiagentcreator.image.adapter.ImageServiceStrategy;
import com.sxxian.multiagentcreator.service.JsonStructuredOutputService;
import com.sxxian.multiagentcreator.service.SkillService;
import com.sxxian.multiagentcreator.utils.ArticlePromptUtils;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
@Slf4j
public class LegacyArticleWorkflow {

    @Resource
    private DashScopeChatModel chatModel;

    @Resource
    private ImageServiceStrategy imageServiceStrategy;

    @Resource
    private ArticleAgent articleAgent;

    @Resource
    private JsonStructuredOutputService jsonStructuredOutputService;

    @Resource
    private ReviewAgent reviewAgent;

    @Resource
    private ImageAgent imageAgent;

    @Resource
    private ImageToolExecutionService imageToolExecutionService;

    @Resource
    private ContentMergeService contentMergeService;

    @Resource
    private SkillService skillService;

    /**
     * 阶段1：生成标题方案（3-5个）
     *
     * @param state         文章状态
     * @param streamHandler 流式输出处理器
     */
    public void executePhase1_GenerateTitles(ArticleState state, Consumer<String> streamHandler) {
        try {
            // 智能体1：生成标题方案
            log.info("阶段1：开始生成标题方案, taskId={}", state.getTaskId());
            ArticleContext context = ArticleContext.fromState(state);
            articleAgent.generateTitles(context).applyToState(state);
            streamHandler.accept(SseMessageTypeEnum.AGENT1_COMPLETE.getValue());
            log.info("阶段1：标题方案生成完成, taskId={}, optionsCount={}",
                    state.getTaskId(), state.getTitleOptions().size());
        } catch (Exception e) {
            rethrowReviewRejected(e);
            log.error("阶段1：标题方案生成失败, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("标题方案生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 阶段2：生成大纲（用户选择标题后）
     *
     * @param state         文章状态
     * @param streamHandler 流式输出处理器
     */
    public void executePhase2_GenerateOutline(ArticleState state, Consumer<String> streamHandler) {
        try {
            // 智能体2：生成大纲（流式输出）
            log.info("阶段2：开始生成大纲, taskId={}", state.getTaskId());
            ArticleContext context = ArticleContext.fromState(state);
            articleAgent.generateOutline(context, streamHandler).applyToState(state);
            streamHandler.accept(SseMessageTypeEnum.AGENT2_COMPLETE.getValue());
            log.info("阶段2：大纲生成完成, taskId={}", state.getTaskId());
        } catch (Exception e) {
            rethrowReviewRejected(e);
            log.error("阶段2：大纲生成失败, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("大纲生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 阶段3：生成正文+配图（用户确认大纲后）
     *
     * @param state         文章状态
     * @param streamHandler 流式输出处理器
     */
    public void executePhase3_GenerateContent(ArticleState state, Consumer<String> streamHandler) {
        try {
            // 获取代理对象
            LegacyArticleWorkflow proxy = getProxy();

            // 智能体3：生成正文（流式输出）
            log.info("阶段3：开始生成正文, taskId={}", state.getTaskId());
            ArticleContext context = ArticleContext.fromState(state);
            articleAgent.generateContent(context, streamHandler).applyToState(state);
            streamHandler.accept(SseMessageTypeEnum.AGENT3_COMPLETE.getValue());

            // 智能体4：分析配图需求
            log.info("阶段3：开始分析配图需求, taskId={}", state.getTaskId());
            imageAgent.planImages(state);
            streamHandler.accept(SseMessageTypeEnum.AGENT4_COMPLETE.getValue());

            // 智能体5：生成配图
            log.info("阶段3：开始生成配图, taskId={}", state.getTaskId());
            ImageExecutionResult imageExecutionResult = imageToolExecutionService.execute(
                    state.getImageRequirements(), state, streamHandler);
            state.setImages(imageExecutionResult.getImages());
            state.getImages().stream()
                    .filter(image -> image.getPosition() != null && image.getPosition() == 1)
                    .findFirst()
                    .ifPresent(image -> state.setCoverImage(image.getUrl()));
            state.setImageReviewResults(imageExecutionResult.getImageReviewResults());
            state.setImageExecutionTraces(imageExecutionResult.getTraces());
            streamHandler.accept(SseMessageTypeEnum.AGENT5_COMPLETE.getValue());

            // 图文合成：将配图插入正文
            log.info("阶段3：开始图文合成, taskId={}", state.getTaskId());
            state.setContent(contentMergeService.insertPlaceholdersIntoContent(
                    state.getContent(), state.getImageRequirements()));
            proxy.mergeImagesIntoContent(state);
            streamHandler.accept(SseMessageTypeEnum.MERGE_COMPLETE.getValue());

            log.info("阶段3：正文生成完成, taskId={}", state.getTaskId());
        } catch (Exception e) {
            rethrowReviewRejected(e);
            log.error("阶段3：正文生成失败, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("正文生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 智能体1：生成标题方案（3-5个）
     */
    @AgentExecution(value = "agent1_generate_titles", description = "生成标题方案", phase = "TITLE_GENERATING")
    public void agent1GenerateTitleOptions(ArticleState state) {
        String prompt = PromptConstant.AGENT1_TITLE_PROMPT
                .replace("{topic}", state.getTopic())
                + skillService.buildPromptInstruction(state.getPlatform(), state.getStyle(), state.getWordRange());

        String content = callLlm(prompt);
        List<ArticleState.TitleOption> titleOptions = jsonStructuredOutputService.parse(
                content,
                new TypeToken<List<ArticleState.TitleOption>>(){},
                StructuredOutputTypeEnum.TITLE_OPTIONS,
                () -> callLlm(prompt),
                1
        );
        state.setTitleOptions(titleOptions);
        log.info("智能体1：标题方案生成成功, optionsCount={}", titleOptions.size());
    }

    /**
     * 智能体2：生成大纲（流式输出）
     */
    @AgentExecution(value = "agent2_generate_outline", description = "生成文章大纲", phase = "OUTLINE_GENERATING")
    public void agent2GenerateOutline(ArticleState state, Consumer<String> streamHandler) {
        // 构建 prompt，根据是否有用户补充描述插入对应部分
        String descriptionSection = "";
        if (state.getUserDescription() != null && !state.getUserDescription().trim().isEmpty()) {
            descriptionSection = PromptConstant.AGENT2_DESCRIPTION_SECTION
                    .replace("{userDescription}", state.getUserDescription());
        }

        String prompt = PromptConstant.AGENT2_OUTLINE_PROMPT
                .replace("{mainTitle}", state.getTitle().getMainTitle())
                .replace("{subTitle}", state.getTitle().getSubTitle())
                .replace("{descriptionSection}", descriptionSection)
                + ArticlePromptUtils.getOutlineWordRangePrompt(state.getWordRange(), state.getStyle())
                + skillService.buildPromptInstruction(state.getPlatform(), state.getStyle(), state.getWordRange());

        String content = callLlmWithStreaming(prompt, streamHandler, SseMessageTypeEnum.AGENT2_STREAMING);
        ArticleState.OutlineResult outlineResult = jsonStructuredOutputService.parse(
                content,
                ArticleState.OutlineResult.class,
                StructuredOutputTypeEnum.OUTLINE_RESULT,
                () -> callLlmWithStreaming(prompt, streamHandler, SseMessageTypeEnum.AGENT2_STREAMING),
                1
        );
        state.setOutline(outlineResult);
        log.info("智能体2：大纲生成成功, sections={}", outlineResult.getSections().size());
    }

    /**
     * 智能体3：生成正文（流式输出）
     */
    @AgentExecution(value = "agent3_generate_content", description = "生成文章正文", phase = "CONTENT_GENERATING")
    public void agent3GenerateContent(ArticleState state, Consumer<String> streamHandler) {
        String outlineText = GsonUtils.toJson(state.getOutline().getSections());
        String prompt = PromptConstant.AGENT3_CONTENT_PROMPT
                .replace("{mainTitle}", state.getTitle().getMainTitle())
                .replace("{subTitle}", state.getTitle().getSubTitle())
                .replace("{outline}", outlineText)
                + skillService.buildPromptInstruction(state.getPlatform(), state.getStyle(), state.getWordRange());

        String content = callLlmWithStreaming(prompt, streamHandler, SseMessageTypeEnum.AGENT3_STREAMING);
        state.setContent(content);
        log.info("智能体3：正文生成成功, length={}", content.length());
    }

    /**
     * 智能体4：分析配图需求（在正文中插入占位符）
     */
    @AgentExecution(value = "agent4_analyze_image_requirements", description = "分析配图需求", phase = "IMAGE_PLANNING")
    public void agent4AnalyzeImageRequirements(ArticleState state) {
        // 构建可用配图方式说明
        String availableMethods = buildAvailableMethodsDescription(state.getEnabledImageMethods());
        // 构建各配图方式的详细使用指南（只包含允许的方式）
        String methodUsageGuide = buildMethodUsageGuide(state.getEnabledImageMethods());

        String prompt = PromptConstant.AGENT4_IMAGE_REQUIREMENTS_PROMPT
                .replace("{mainTitle}", state.getTitle().getMainTitle())
                .replace("{style}", skillService.resolve(state.getPlatform(), state.getStyle()).getDisplayName())
                .replace("{wordRange}", state.getWordRange() == null ? "" : state.getWordRange())
                .replace("{contentLength}", String.valueOf(state.getContent() == null ? 0 : state.getContent().length()))
                .replace("{content}", state.getContent())
                .replace("{imageCountGuide}", "请结合写作 Skill 和文章长度决定配图数量，避免低价值配图。")
                .replace("{availableMethods}", availableMethods)
                .replace("{methodUsageGuide}", methodUsageGuide
                        + "\n写作 Skill 图片建议：\n"
                        + skillService.resolve(state.getPlatform(), state.getStyle()).getImageGuidance());

        ArticleState.Agent4Result agent4Result = generateImagePlan(prompt);

        // 更新正文为包含占位符的版本
        state.setContent(agent4Result.getContentWithPlaceholders());

        // 验证并过滤配图需求，确保所有 imageSource 都在允许列表中
        List<ArticleState.ImageRequirement> validatedRequirements = validateAndFilterImageRequirements(
                agent4Result.getImageRequirements(),
                state.getEnabledImageMethods()
        );

        state.setImageRequirements(validatedRequirements);
        ReviewResult reviewResult = reviewAgent.reviewImagePlan(state);
        state.setImagePlanReviewResult(reviewResult);
        if (!reviewResult.isApprovedByThreshold()) {
            String revisedPrompt = prompt + reviewAgent.buildRevisionAdvice(reviewResult);
            agent4Result = generateImagePlan(revisedPrompt);
            state.setContent(agent4Result.getContentWithPlaceholders());
            validatedRequirements = validateAndFilterImageRequirements(
                    agent4Result.getImageRequirements(),
                    state.getEnabledImageMethods()
            );
            state.setImageRequirements(validatedRequirements);
            reviewResult = reviewAgent.reviewImagePlan(state);
            state.setImagePlanReviewResult(reviewResult);
            if (!reviewResult.isApprovedByThreshold()) {
                throw new ReviewRejectedException("IMAGE_REVIEWING", reviewResult);
            }
        }
        log.info("智能体4：配图需求分析成功, count={}, validated={}, 已在正文中插入占位符",
                agent4Result.getImageRequirements().size(), validatedRequirements.size());
    }

    private ArticleState.Agent4Result generateImagePlan(String prompt) {
        String content = callLlm(prompt);
        return jsonStructuredOutputService.parse(
                content,
                ArticleState.Agent4Result.class,
                StructuredOutputTypeEnum.IMAGE_PLAN,
                () -> callLlm(prompt),
                1
        );
    }

    /**
     * 智能体5：生成配图（串行执行，支持混用多种配图方式，统一上传到 COS）
     */
    @AgentExecution(value = "agent5_generate_images", description = "生成配图", phase = "IMAGE_EXECUTING")
    public void agent5GenerateImages(ArticleState state, Consumer<String> streamHandler) {
        List<ArticleState.ImageResult> imageResults = new ArrayList<>();
        List<ImageReviewResult> imageReviewResults = new ArrayList<>();

        for (ArticleState.ImageRequirement requirement : state.getImageRequirements()) {
            String imageSource = requirement.getImageSource();
            log.info("智能体5：开始获取配图, position={}, imageSource={}, keywords={}",
                    requirement.getPosition(), imageSource, requirement.getKeywords());

            // 构建图片请求对象
            ImageRequest imageRequest = ImageRequest.builder()
                    .keywords(requirement.getKeywords())
                    .prompt(requirement.getPrompt())
                    .position(requirement.getPosition())
                    .type(requirement.getType())
                    .build();

            // 使用策略模式获取图片并统一上传到 COS
            ImageServiceStrategy.ImageResult result = imageServiceStrategy.getImageAndUpload(imageSource, imageRequest);

            String cosUrl = result.getUrl();
            ImageMethodEnum method = result.getMethod();

            // 创建配图结果（URL 已经是 COS 地址）
            ArticleState.ImageResult imageResult = buildImageResult(requirement, cosUrl, method);
            imageResults.add(imageResult);
            imageReviewResults.add(reviewAgent.reviewImageResult(state, requirement, imageResult));

            // 推送单张配图完成
            String imageCompleteMessage = SseMessageTypeEnum.IMAGE_COMPLETE.getStreamingPrefix() + GsonUtils.toJson(imageResult);
            streamHandler.accept(imageCompleteMessage);

            log.info("智能体5：配图获取并上传成功, position={}, method={}, cosUrl={}",
                    requirement.getPosition(), method.getValue(), cosUrl);
        }

        state.setImages(imageResults);
        state.setImageReviewResults(imageReviewResults);
        log.info("智能体5：所有配图生成并上传完成, count={}", imageResults.size());
    }

    /**
     * 图文合成：根据占位符将配图插入正文
     */
    @AgentExecution(value = "agent6_merge_content", description = "图文合成", phase = "MERGING")
    public void mergeImagesIntoContent(ArticleState state) {
        String content = state.getContent();
        List<ArticleState.ImageResult> images = state.getImages();

        if (images == null || images.isEmpty()) {
            state.setFullContent(content);
            return;
        }

        String fullContent = content;

        // 遍历所有配图，根据占位符替换为实际图片
        for (ArticleState.ImageResult image : images) {
            if (image.getPosition() != null && image.getPosition() == 1) {
                if (image.getUrl() != null && !image.getUrl().isBlank() && !fullContent.contains(image.getUrl())) {
                    fullContent = "![" + image.getDescription() + "](" + image.getUrl() + ")\n\n" + fullContent;
                }
                continue;
            }
            String placeholder = image.getPlaceholderId();
            if (placeholder != null && !placeholder.isEmpty()) {
                String imageMarkdown = "![" + image.getDescription() + "](" + image.getUrl() + ")";
                fullContent = fullContent.replace(placeholder, imageMarkdown);
            }
        }

        state.setFullContent(fullContent);
        log.info("图文合成完成, fullContentLength={}", fullContent.length());
    }

    // region 辅助方法

    /**
     * 调用 LLM（非流式）
     */
    private String callLlm(String prompt) {
        ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
        return response.getResult().getOutput().getText();
    }

    /**
     * 调用 LLM（流式输出）
     */
    private String callLlmWithStreaming(String prompt, Consumer<String> streamHandler, SseMessageTypeEnum messageType) {
        StringBuilder contentBuilder = new StringBuilder();

        Flux<ChatResponse> streamResponse = chatModel.stream(new Prompt(new UserMessage(prompt)));

        streamResponse
                .doOnNext(response -> {
                    String chunk = response.getResult().getOutput().getText();
                    if (chunk != null && !chunk.isEmpty()) {
                        contentBuilder.append(chunk);
                        streamHandler.accept(messageType.getStreamingPrefix() + chunk);
                    }
                })
                .doOnError(error -> log.error("LLM 流式调用失败, messageType={}", messageType, error))
                .blockLast();

        return contentBuilder.toString();
    }

    /**
     * 构建配图结果
     */
    private ArticleState.ImageResult buildImageResult(ArticleState.ImageRequirement requirement,
                                                      String imageUrl,
                                                      ImageMethodEnum method) {
        ArticleState.ImageResult imageResult = new ArticleState.ImageResult();
        imageResult.setPosition(requirement.getPosition());
        imageResult.setUrl(imageUrl);
        imageResult.setMethod(method.getValue());
        imageResult.setKeywords(requirement.getKeywords());
        imageResult.setSectionTitle(requirement.getSectionTitle());
        imageResult.setDescription(requirement.getType());
        imageResult.setPlaceholderId(requirement.getPlaceholderId());
        return imageResult;
    }

    /**
     * 构建可用配图方式说明
     */
    private String buildAvailableMethodsDescription(List<String> enabledMethods) {
        // 如果为空或 null，表示支持所有方式
        if (enabledMethods == null || enabledMethods.isEmpty()) {
            return getAllMethodsDescription();
        }

        // 只描述允许的方式
        StringBuilder sb = new StringBuilder();
        for (String method : enabledMethods) {
            ImageMethodEnum methodEnum = ImageMethodEnum.getByValue(method);
            if (methodEnum != null && !methodEnum.isFallback()) {
                sb.append("   - ").append(methodEnum.getValue())
                        .append(": ").append(getMethodUsageDescription(methodEnum))
                        .append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 获取所有配图方式的完整描述
     */
    private String getAllMethodsDescription() {
        return """
               - CHINA_IMAGE_SEARCH: 适合中文影视/动漫/IP角色/明星/品牌/热点事件等具体实体，优先检索国内网站的官方海报、剧照、角色图、新闻图
               - PEXELS: 适合通用真实场景、产品照片、人物照片、自然风景等写实图片
               - QWEN_IMAGE: 适合创意插画、信息图表、需要文字渲染、抽象概念、艺术风格等 AI 生成图片
               - GRAPHVIZ: 适合流程图、架构图、依赖关系图等结构化图表，优先用于流程图
               - MERMAID: 适合时序图、甘特图和 Graphviz 不适合的结构化图表
               - ICONIFY: 适合图标、符号、小型装饰性图标（如：箭头、勾选、星星、心形等）
               - EMOJI_PACK: 适合表情包、搞笑图片、轻松幽默的配图
               - SVG_DIAGRAM: 适合概念示意图、思维导图样式、逻辑关系展示（不涉及精确数据）
               """;
    }

    /**
     * 获取配图方式的使用说明
     */
    private String getMethodUsageDescription(ImageMethodEnum method) {
        return switch (method) {
            case CHINA_IMAGE_SEARCH -> "适合中文影视/动漫/IP角色/明星/品牌/热点事件等具体实体，优先检索国内网站的官方海报、剧照、角色图、新闻图";
            case PEXELS -> "适合通用真实场景、产品照片、人物照片、自然风景等写实图片";
            case QWEN_IMAGE -> "适合创意插画、信息图表、需要文字渲染、抽象概念、艺术风格等 AI 生成图片";
            case NANO_BANANA -> "旧 Nano Banana AI 生图，当前不作为默认文生图路径";
            case GRAPHVIZ -> "适合流程图、架构图、依赖关系图等结构化图表，优先用于流程图";
            case MERMAID -> "适合时序图、甘特图和 Graphviz 不适合的结构化图表";
            case ICONIFY -> "适合图标、符号、小型装饰性图标（如：箭头、勾选、星星、心形等）";
            case EMOJI_PACK -> "适合表情包、搞笑图片、轻松幽默的配图";
            case SVG_DIAGRAM -> "适合概念示意图、思维导图样式、逻辑关系展示（不涉及精确数据）";
            default -> method.getDescription();
        };
    }

    /**
     * 构建配图方式的详细使用指南（只包含允许的方式）
     */
    private String buildMethodUsageGuide(List<String> enabledMethods) {
        // 如果没有限制，返回所有方式的使用指南
        List<String> methodsToInclude = (enabledMethods == null || enabledMethods.isEmpty())
                ? List.of("CHINA_IMAGE_SEARCH", "PEXELS", "QWEN_IMAGE", "GRAPHVIZ", "MERMAID", "ICONIFY", "EMOJI_PACK", "SVG_DIAGRAM")
                : enabledMethods;

        StringBuilder sb = new StringBuilder();

        for (String method : methodsToInclude) {
            String guide = getMethodDetailedGuide(method);
            if (guide != null && !guide.isEmpty()) {
                sb.append(guide).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 获取单个配图方式的详细使用指南
     */
    private String getMethodDetailedGuide(String method) {
        return switch (method) {
            case "CHINA_IMAGE_SEARCH" -> """
                    - CHINA_IMAGE_SEARCH: 提供中文关键词(keywords)，优先包含精确实体名 + 官方海报/剧照/角色图/上映/新闻图等限定词。适用于中文影视、动漫 IP、明星、品牌、热点事件。prompt 留空。""";
            case "PEXELS" -> """
                    - PEXELS: 提供英文搜索关键词(keywords)，要准确、具体。prompt 留空。""";
            case "QWEN_IMAGE" -> """
                    - QWEN_IMAGE: 提供详细的英文或中文生图提示词(prompt)，描述场景、风格、细节。keywords 留空。""";
            case "NANO_BANANA" -> """
                    - NANO_BANANA: 旧 AI 生图路径，除非用户明确选择，否则优先改用 QWEN_IMAGE。提供详细 prompt，keywords 留空。""";
            case "GRAPHVIZ" -> """
                    - GRAPHVIZ: 在 prompt 字段生成完整 Graphviz DOT 代码。必须以 digraph/graph 开头；只输出 DOT，不要 markdown fence 或解释文字；流程图、架构图优先用 digraph + rankdir=LR；节点标签用双引号，长中文标签用 \\n 拆行；节点数量控制在 8 个以内，避免巨大留白；keywords 留空。""";
            case "MERMAID" -> """
                    - MERMAID: 在 prompt 字段生成完整 Mermaid 代码。Graphviz 不可用或需要时序图/甘特图时使用；keywords 留空。""";
            case "ICONIFY" -> """
                    - ICONIFY: 提供英文图标关键词(keywords)，如：check、arrow、star、heart。prompt 留空。""";
            case "EMOJI_PACK" -> """
                    - EMOJI_PACK: 提供中文或英文关键词(keywords)描述表情内容。prompt 留空。系统会自动添加"表情包"搜索。""";
            case "SVG_DIAGRAM" -> """
                    - SVG_DIAGRAM: 在 prompt 字段描述示意图需求（中文），说明要表达的概念和关系。keywords 留空。
                      示例：绘制思维导图样式的图，中心是"自律"，周围4个分支：习惯、环境、反馈、系统""";
            default -> null;
        };
    }

    /**
     * 验证并过滤配图需求
     * 确保所有 imageSource 都在允许列表中
     *
     * @param requirements    原始配图需求列表
     * @param enabledMethods  允许的配图方式列表
     * @return 验证后的配图需求列表
     */
    private List<ArticleState.ImageRequirement> validateAndFilterImageRequirements(
            List<ArticleState.ImageRequirement> requirements,
            List<String> enabledMethods) {

        // 如果没有限制，返回所有需求
        if (enabledMethods == null || enabledMethods.isEmpty()) {
            return requirements;
        }

        List<ArticleState.ImageRequirement> validatedRequirements = new ArrayList<>();

        for (ArticleState.ImageRequirement req : requirements) {
            String imageSource = req.getImageSource();

            // 验证 imageSource 是否在允许列表中
            if (enabledMethods.contains(imageSource)) {
                validatedRequirements.add(req);
                log.debug("配图需求验证通过, position={}, imageSource={}", req.getPosition(), imageSource);
            } else {
                log.warn("配图需求不符合限制被过滤, position={}, imageSource={}, enabledMethods={}",
                        req.getPosition(), imageSource, enabledMethods);

                // 尝试替换为允许的方式（优先使用第一个允许的方式）
                if (!enabledMethods.isEmpty()) {
                    String fallbackSource = enabledMethods.get(0);
                    req.setImageSource(fallbackSource);
                    validatedRequirements.add(req);
                    log.info("配图需求已替换为允许的方式, position={}, fallback={}",
                            req.getPosition(), fallbackSource);
                }
            }
        }

        return validatedRequirements;
    }

    /**
     * AI 修改大纲
     *
     * @param mainTitle        主标题
     * @param subTitle         副标题
     * @param currentOutline   当前大纲
     * @param modifySuggestion 用户修改建议
     * @return 修改后的大纲
     */
    @AgentExecution(value = "ai_modify_outline", description = "AI修改大纲", phase = "OUTLINE_GENERATING")
    public List<ArticleState.OutlineSection> aiModifyOutline(String mainTitle, String subTitle,
                                                             List<ArticleState.OutlineSection> currentOutline,
                                                             String modifySuggestion) {
        String currentOutlineJson = GsonUtils.toJson(currentOutline);

        String prompt = PromptConstant.AI_MODIFY_OUTLINE_PROMPT
                .replace("{mainTitle}", mainTitle)
                .replace("{subTitle}", subTitle)
                .replace("{currentOutline}", currentOutlineJson)
                .replace("{modifySuggestion}", modifySuggestion);

        String content = callLlm(prompt);
        ArticleState.OutlineResult outlineResult = jsonStructuredOutputService.parse(
                content,
                ArticleState.OutlineResult.class,
                StructuredOutputTypeEnum.OUTLINE_RESULT,
                () -> callLlm(prompt),
                1
        );

        log.info("AI修改大纲成功, sectionsCount={}", outlineResult.getSections().size());
        return outlineResult.getSections();
    }

    /**
     * 获取当前类的代理对象
     * 用于解决 Spring AOP 同类方法调用代理失效问题
     */
    private LegacyArticleWorkflow getProxy() {
        try {
            return (LegacyArticleWorkflow) AopContext.currentProxy();
        } catch (IllegalStateException e) {
            // 如果获取代理失败，返回 this（降级处理）
            log.warn("获取 AOP 代理对象失败，使用原始对象: {}", e.getMessage());
            return this;
        }
    }

    private void rethrowReviewRejected(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof ReviewRejectedException reviewRejectedException) {
                throw reviewRejectedException;
            }
            current = current.getCause();
        }
    }

    // endregion
}
