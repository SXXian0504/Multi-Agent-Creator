package com.sxxian.multiagentcreator.constant;

/**
 * Prompt模板
 */
public interface PromptConstant {

    /**
     * 智能体1：生成标题方案
     */
    String AGENT1_TITLE_PROMPT = """
            你是一位爆款文章标题专家,擅长创作吸引人的标题。
            
            根据以下选题,生成 3-5 个爆款文章标题方案:
            选题：{topic}
            
            要求:
            1. 每个方案包含主标题和副标题
            2. 主标题要包含数字、情绪化词汇,吸引眼球
            3. 副标题要补充说明,增强吸引力
            4. 标题要简洁有力,不超过30字
            5. 不同方案要有不同的切入角度
            6. 符合新媒体爆款文章的风格
            
            请直接返回 JSON 格式,不要有其他内容:
            [
              {
                "mainTitle": "主标题1",
                "subTitle": "副标题1"
              },
              {
                "mainTitle": "主标题2",
                "subTitle": "副标题2"
              },
              {
                "mainTitle": "主标题3",
                "subTitle": "副标题3"
              }
            ]
            """;

    /**
     * 智能体2：生成大纲
     */
    String AGENT2_OUTLINE_PROMPT = """
            你是一位专业的文章策划师,擅长设计文章结构。
            
            根据以下标题,生成文章大纲:
            主标题：{mainTitle}
            副标题：{subTitle}
            {descriptionSection}
            
            要求:
            1. 大纲要有清晰的逻辑结构
            2. 章节数量、章节边界和要点密度必须服务于选题、风格和用户选择的字数范围
            3. 每个章节要有明确的标题和核心要点，短文要压缩要点，长文可以展开层次
            4. 如果后续字数范围要求给出更具体的章节数量和要点数量，请优先遵守字数范围要求
            
            请直接返回 JSON 格式,不要有其他内容:
            {
              "sections": [
                {
                  "section": 1,
                  "title": "章节标题",
                  "points": ["要点1", "要点2"]
                }
              ]
            }
            """;

    /**
     * 用户补充描述部分（动态插入到 AGENT2_OUTLINE_PROMPT）
     */
    String AGENT2_DESCRIPTION_SECTION = """
            
            用户补充要求：{userDescription}
            请在大纲中充分体现用户的补充要求。
            """;

    /**
     * SVG 概念示意图生成 Prompt
     */
    String SVG_DIAGRAM_GENERATION_PROMPT = """
            ### 背景 ###
            你是一位资深的信息可视化设计师，擅长将抽象概念转化为直观易懂的 SVG 示意图。
            你的作品曾用于知名媒体和技术文档，风格简洁现代、逻辑清晰。
            
            ### 需求 ###
            {requirement}
            
            ### 任务步骤 ###
            1. 分析需求：理解要表达的核心概念和逻辑关系
            2. 设计布局：确定图形的整体结构（中心辐射、层级、流程等）
            3. 选择元素：使用圆形、矩形、箭头、连线等基础图形
            4. 配色美化：应用现代配色方案，确保视觉协调
            5. 生成代码：输出完整规范的 SVG 代码
            
            ### 技术规范 ###
            - 必须包含 <?xml version="1.0" encoding="UTF-8"?> 声明
            - 必须设置 viewBox="0 0 800 600"，便于自适应缩放
            - 字体使用 font-family="Arial, sans-serif"，确保跨平台兼容
            - 使用语义化的 id 和 class 命名
            
            ### 设计风格 ###
            - 配色：蓝色系为主（#4A90D9、#6BB3F0、#E8F4FC），辅以渐变效果
            - 布局：留白充足，元素间距均匀，层次分明
            - 文字：标签简洁，字号适中（14-18px），颜色对比清晰
            - 连线：使用带箭头的线条表示方向和关系，线条粗细 2-3px
            
            ### 输出要求 ###
            直接返回完整的 SVG XML 代码，不要有任何解释或其他内容。
            """;

    /**
     * 智能体3：生成正文
     */
    String AGENT3_CONTENT_PROMPT = """
            你是一位资深的内容创作者,擅长撰写优质文章。
            
            根据以下大纲,创作文章正文:
            主标题：{mainTitle}
            副标题：{subTitle}
            大纲：
            {outline}
            
            要求:
            1. 内容详略应匹配选题、大纲、文章风格和用户选择的字数范围
            2. 语言流畅,富有感染力
            3. 适当使用金句,增强可读性
            4. 添加过渡句,确保逻辑连贯
            5. 使用 Markdown 格式,章节使用 ## 标题
            
            请直接返回 Markdown 格式的正文内容,不要有其他内容。
            """;

    /**
     * 智能体4：分析配图需求（支持多种图片来源，使用占位符方案）
     */
    String AGENT4_IMAGE_REQUIREMENTS_PROMPT = """
            你是一位专业的新媒体编辑,擅长为文章配图。
            
            根据以下文章内容,分析配图需求,并在正文中插入图片占位符:
            主标题：{mainTitle}
            文章风格：{style}
            字数范围：{wordRange}
            正文长度：{contentLength}
            正文：
            {content}

            【配图数量策略】
            {imageCountGuide}
            
            【重要】可用的配图方式（请严格只从以下方式中选择，禁止使用未列出的方式）：
            {availableMethods}
            
            各配图方式的使用要求：
            {methodUsageGuide}
            
            通用要求:
            1. 识别需要配图的位置(封面、关键章节、段落之间等)
            2. 根据文章内容和结构灵活决定配图数量，严格遵守【配图数量策略】，避免为了凑数量插入低价值图片
            3. **在正文中插入占位符**：使用以下两种格式
               - 普通图片占位符：{{IMAGE_PLACEHOLDER_N}}，其中 N 为配图序号（1, 2, 3...），必须独占一行
               - Icon 占位符：{{ICON_PLACEHOLDER_N}}，可以放在文字行内任意位置（用于 ICONIFY 类型）
               - 注意：position=1 的封面图不需要占位符，不要放在正文中
               - 其他配图占位符可以放在任意合适位置（章节标题后、段落之间、列表项中、文字行内等）
            4. **imageSource 字段必须且只能是上述可用配图方式之一，不要使用其他值**
            5. placeholderId 必须与正文中插入的占位符完全一致
            6. position=1 为封面图
            7. 每个 imageRequirement 必须填写 reason，说明为什么在该位置配图、为什么选择该 imageSource、为什么使用该 keywords 或 prompt
            
            请直接返回 JSON 格式,不要有其他内容:
            {
              "contentWithPlaceholders": "",
              "imageRequirements": [
                {
                  "position": 1,
                  "type": "cover",
                  "sectionTitle": "",
                  "imageSource": "QWEN_IMAGE",
                  "reason": "封面图用于概括全文主题，AI 生成图更适合表达抽象科技概念",
                  "keywords": "",
                  "prompt": "A modern minimalist illustration of AI technology concept, featuring abstract neural network patterns with blue and purple gradient colors, clean design suitable for article cover, 16:9 aspect ratio",
                  "placeholderId": ""
                },
                {
                  "position": 2,
                  "type": "section",
                  "sectionTitle": "章节标题1",
                  "imageSource": "PEXELS",
                  "reason": "该章节需要真实工作场景支撑，图库检索更适合获得自然照片",
                  "keywords": "business success teamwork office",
                  "prompt": "",
                  "placeholderId": "{{IMAGE_PLACEHOLDER_1}}"
                },
                {
                  "position": 3,
                  "type": "inline",
                  "sectionTitle": "",
                  "imageSource": "ICONIFY",
                  "reason": "该位置只需要轻量符号强化列表含义，图标比完整配图更克制",
                  "keywords": "check circle",
                  "prompt": "",
                  "placeholderId": "{{ICON_PLACEHOLDER_1}}"
                },
                {
                  "position": 4,
                  "type": "section",
                  "sectionTitle": "章节标题2",
                  "imageSource": "MERMAID",
                  "reason": "该章节解释流程关系，Mermaid 适合表达结构化步骤",
                  "keywords": "",
                  "prompt": "flowchart TB\\n    A[用户请求] --> B[负载均衡]\\n    B --> C[应用服务器]",
                  "placeholderId": "{{IMAGE_PLACEHOLDER_2}}"
                }
              ]
            }
            """;

    String AGENT4_OUTLINE_IMAGE_REQUIREMENTS_PROMPT = """
            你是 ImageAgent，负责在正文尚未生成时，基于用户主题、标题和已确认大纲提前规划配图。

            任务上下文：
            主题：{topic}
            主标题：{mainTitle}
            副标题：{subTitle}
            用户补充描述：{userDescription}
            文章风格：{style}
            字数范围：{wordRange}
            大纲：
            {outline}

            【配图数量策略】
            {imageCountGuide}

            【可用配图方式】
            {availableMethods}

            各配图方式的使用要求：
            {methodUsageGuide}

            要求：
            1. 正文还没有生成，只规划视觉方向、图片类型、章节归属和工具参数，不要输出 contentWithPlaceholders。
            2. position=1 必须是封面图，placeholderId 为空。
            3. 非封面图必须尽量绑定到大纲中的 sectionTitle，placeholderId 可以填写 {{IMAGE_PLACEHOLDER_N}} 或 {{ICON_PLACEHOLDER_N}}，但不要假设正文已包含它。
            4. 严格遵守配图数量策略，不要为了凑数量添加低价值图片；短文或营销文可以只有封面图。
            5. imageSource 必须从可用配图方式中选择。
            6. 每个 imageRequirement 必须填写 reason，说明为什么在该位置配图、为什么选择该 imageSource、为什么使用该 keywords 或 prompt。

            请直接返回 JSON，不要输出其他内容：
            {
              "imageRequirements": [
                {
                  "position": 1,
                  "type": "cover",
                  "sectionTitle": "",
                  "imageSource": "QWEN_IMAGE",
                  "reason": "封面图用于概括全文主题，AI 生成更适合表达抽象视觉方向",
                  "keywords": "",
                  "prompt": "A clean editorial cover image for the article topic, 16:9 aspect ratio",
                  "placeholderId": ""
                },
                {
                  "position": 2,
                  "type": "section",
                  "sectionTitle": "大纲章节标题",
                  "imageSource": "PEXELS",
                  "reason": "该章节需要真实场景支撑，图库检索更适合获得自然照片",
                  "keywords": "business teamwork office",
                  "prompt": "",
                  "placeholderId": "{{IMAGE_PLACEHOLDER_1}}"
                }
              ]
            }
            """;

    String IMAGE_REPLAN_PROMPT = """
            你是 ImageAgent，负责基于图片评审 observation 对单张图片重新规划。

            任务上下文：
            主题：{topic}
            主标题：{mainTitle}
            章节：{sectionTitle}
            可用配图方式：
            {availableMethods}

            原始配图需求：
            {imageRequirement}

            上一轮工具结果：
            {imageResult}

            图片评审 observation：
            {observation}

            修订建议：
            {revisionAdvice}

            要求：
            1. 只重写这一张图片的配图需求，不要改其他图片。
            2. 如果是 Mermaid/SVG 渲染失败，优先修复 prompt 中的图表代码或图表描述。
            3. 如果是 Graphviz 渲染失败，优先切换为 MERMAID 并生成等价 Mermaid 代码；如果是 Mermaid 渲染失败，优先切换为 GRAPHVIZ 并生成等价 DOT 代码。
            4. 如果是相关性低，优先改 keywords 或 prompt；必要时才切换 imageSource。
            5. imageSource 必须从可用配图方式中选择。
            6. 必须填写 reason，说明本轮重规划的依据。
            7. 返回 JSON 对象，字段与 ImageRequirement 完全一致。

            请直接返回 JSON，不要输出其他内容。
            """;

    // region 文章风格 Prompt

    /**
     * 科技风格 Prompt 附加
     */
    String STYLE_TECH_PROMPT = """
            
            **重要：请使用科技风格进行创作**
            - 语言专业、严谨，多使用专业术语和行业词汇
            - 逻辑清晰，重视数据和事实支撑
            - 叙述客观理性，避免主观情感表达
            - 突出技术创新、发展趋势、解决方案
            - 可适当引用权威资料或专家观点
            """;

    /**
     * 情感风格 Prompt 附加
     */
    String STYLE_EMOTIONAL_PROMPT = """
            
            **重要：请使用情感风格进行创作**
            - 语言温暖细腻，富有感染力和共鸣
            - 善用比喻、排比等修辞手法增强表现力
            - 注重情感表达，讲述真实故事和感悟
            - 引发读者情感共鸣，传递正能量
            - 适当使用抒情语句，增加文章温度
            """;

    /**
     * 教育风格 Prompt 附加
     */
    String STYLE_EDUCATIONAL_PROMPT = """
            
            **重要：请使用教育风格进行创作**
            - 语言通俗易懂，深入浅出地讲解概念
            - 结构清晰，循序渐进，便于学习理解
            - 多用案例、类比帮助读者理解复杂内容
            - 总结重点知识点，提供实用的学习建议
            - 鼓励思考，启发读者自主学习和探索
            """;

    /**
     * 轻松幽默风格 Prompt 附加
     */
    String STYLE_HUMOROUS_PROMPT = """
            
            **重要：请使用轻松幽默风格进行创作**
            - 语言轻松活泼，幽默风趣
            - 善用网络流行语、俏皮话和有趣的比喻
            - 适当自嘲或调侃，增加趣味性
            - 内容轻松易读，让读者在愉快中获取信息
            - 可加入一些有趣的段子或梗，但不失专业性
            """;

    /**
     * 用户选择的字数范围附加 Prompt。
     */
    String WORD_RANGE_PROMPT = """
            
            **字数范围要求**
            {wordRangeInstruction}
            """;

    /**
     * 商品营销风格 Prompt 附加
     */
    String STYLE_MARKETING_PROMPT = """
            
            **重要：请使用商品营销风格进行创作**
            - 明确目标用户、使用场景和核心痛点
            - 用利益表达承接卖点，让读者理解产品能带来的具体改变
            - 建立信任感，可使用合理的场景、案例、机制说明或风险提示
            - 结尾提供自然、具体、不过度强迫的行动引导
            - 禁止虚假承诺、夸大疗效、不可验证数据和制造焦虑式营销
            """;

    /**
     * 通用评审 Prompt。根据 stage、styleRubric、stageRubric 组合使用。
     */
    String REVIEW_PROMPT = """
            你是内容质量评审智能体，只负责语义质量评审，不负责基础 JSON 格式校验。
            
            评审对象阶段：{stageName}
            当前文章风格：{styleName}
            主题：{topic}
            主标题：{mainTitle}
            副标题：{subTitle}
            用户补充要求：{userDescription}
            
            【内容结构画像】
            {contentProfile}
            
            【待评审内容】
            {content}
            
            【评分规则】
            总分 100，80 分及以上通过。
            
            通用底线 40 分：
            - 主题相关性 10：是否紧扣选题、标题和用户补充要求。
            - 结构完整性 10：标题、大纲、正文是否前后一致，段落推进是否清楚。
            - 表达清晰度 10：语言是否顺畅、可读、无明显重复或跳跃。
            - 合规与事实底线 10：不得编造明确事实、不得出现明显违法违规或危险建议。
            
            风格特化 45 分：
            {styleRubric}
            
            阶段适配 15 分：
            {stageRubric}
            
            【分数校准】
            - 95-100：几乎无需修改，且能指出待评审内容中至少 3 个具体亮点。
            - 88-94：质量较高，仅有轻微优化点。
            - 80-87：基本可用，但必须指出至少 1 个具体缺陷和 1 个具体修改建议。
            - 70-79：结构、风格或内容价值存在明显短板，应进入 REVISE。
            - 60-69：多处关键缺陷，读者价值或可写性不足。
            - 0-59：明显跑题、空泛、缺失核心内容或存在严重风险。
            
            要求：
            1. approved 必须等于 score >= 80。
            2. dimensionScores 使用英文 key，分数为整数，且 commonBaseline + styleFit + stageFit 必须等于 score。
            3. problems 必须指出具体问题，不要空泛；如果 score < 95，problems 不能为空。
            4. suggestions 必须能直接作为被评审 Agent 的下一轮改进方向；如果 score < 95，suggestions 不能为空。
            5. nextAction 只能取 APPROVE、REVISE、FALLBACK、USER_CONFIRM。
            6. 必须根据待评审内容逐项独立打分，禁止照抄示例分数或固定输出同一分数。
            7. 禁止把 85 当作默认分。只有在内容“基本可用但存在明确轻微缺陷”时才能给 85，并且必须在 problems 中写出该缺陷对应的具体内容。
            8. problems 和 suggestions 必须引用或复述待评审内容中的具体标题、章节、段落、卖点、论据或表达问题，不能只写“建议优化结构/增强吸引力”这类泛化意见。
            
            请直接返回 JSON，不要输出其他内容。JSON 字段必须包含：
            - approved: boolean
            - score: 0 到 100 的整数
            - dimensionScores.commonBaseline: 0 到 40 的整数
            - dimensionScores.styleFit: 0 到 45 的整数
            - dimensionScores.stageFit: 0 到 15 的整数
            - problems: 字符串数组，指出具体问题
            - suggestions: 字符串数组，给出可直接执行的修改建议
            - nextAction: APPROVE、REVISE、FALLBACK、USER_CONFIRM 之一
            """;

    /**
     * 图片结果评审 Prompt。
     */
    String IMAGE_REVIEW_PROMPT = """
            你是图片质量评审智能体，负责判断图片结果是否满足文章配图需求。
            
            主题：{topic}
            主标题：{mainTitle}
            正文片段或章节：{sectionTitle}
            
            【配图需求】
            {imageRequirement}
            
            【图片结果】
            {imageResult}
            
            【评分规则】
            总分 100，80 分及以上通过。
            - 相关性 30：图片是否匹配章节、关键词、prompt 和正文位置。
            - 可用性 25：URL、方法、描述等结果是否可用于图文合成。
            - 视觉质量 25：是否适合文章阅读场景，避免明显低质、跑题或违和。
            - 位置匹配 20：是否与 placeholder、position、sectionTitle 一致。
            
            要求：
            1. approved 必须等于 score >= 80。
            2. observation 描述图片当前问题或通过原因。
            3. revisionAdvice 给出后续重规划时可直接使用的建议。
            4. nextAction 只能取 APPROVE、REPLAN、FALLBACK、USER_CONFIRM。
            5. 必须结合图片 URL 对应的实际视觉内容和配图需求打分，禁止固定输出同一分数。
            
            请直接返回 JSON，不要输出其他内容。JSON 字段必须包含：
            - approved: boolean
            - score: 0 到 100 的整数
            - problems: 字符串数组，指出具体问题
            - suggestions: 字符串数组，给出具体建议
            - nextAction: APPROVE、REPLAN、FALLBACK、USER_CONFIRM 之一
            - observation: 图片当前问题或通过原因
            - revisionAdvice: 后续重规划时可直接使用的建议
            """;

    /**
     * AI 修改大纲 Prompt
     */
    String AI_MODIFY_OUTLINE_PROMPT = """
            你是一位专业的文章策划师,擅长根据用户反馈优化文章结构。
            
            当前文章信息：
            主标题：{mainTitle}
            副标题：{subTitle}
            
            当前大纲：
            {currentOutline}
            
            用户修改建议：
            {modifySuggestion}
            
            要求：
            1. 根据用户的修改建议，调整大纲结构
            2. 保持大纲的逻辑性和完整性
            3. 如果用户建议删除某章节，则删除；建议增加则增加；建议修改则修改
            4. 保持 JSON 格式不变
            5. 章节序号自动重新排序
            
            请直接返回修改后的 JSON 格式大纲，不要有其他内容：
            {
              "sections": [
                {
                  "section": 1,
                  "title": "章节标题",
                  "points": ["要点1", "要点2"]
                }
              ]
            }
            """;

    // endregion
}
