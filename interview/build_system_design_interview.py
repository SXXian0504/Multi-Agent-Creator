# -*- coding: utf-8 -*-
import html
import os
import re
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent
XLSX_PATH = ROOT / "multi-agent-creator-interview-qa.xlsx"
HTML_PATH = ROOT / "multi-agent-creator-interview-qa.html"

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def read_existing_rows(path):
    if not path.exists():
        return [["序号", "问题", "回答"]]
    try:
        with zipfile.ZipFile(path) as z:
            root = ET.fromstring(z.read("xl/worksheets/sheet1.xml"))
            rows = []
            for row in root.find("m:sheetData", NS).findall("m:row", NS):
                vals = []
                for c in row.findall("m:c", NS):
                    t = c.find("m:is/m:t", NS)
                    v = c.find("m:v", NS)
                    vals.append(t.text if t is not None else (v.text if v is not None else ""))
                rows.append(vals)
            return rows or [["序号", "问题", "回答"]]
    except Exception:
        return [["序号", "问题", "回答"]]


def clean_existing(rows):
    header = ["序号", "问题", "回答"]
    data = rows[1:] if rows and rows[0][:3] == header else rows
    filtered = []
    for row in data:
        if len(row) >= 3 and row[1] and not str(row[1]).startswith("【系统设计与可解释性】"):
            filtered.append([str(len(filtered) + 1), row[1], row[2]])
    return [header] + filtered


SYSTEM_QA = [
    (
        "【系统设计与可解释性】请用文字画出项目核心链路。",
        """设计目的：把面向用户的一次创作请求拆成可解释、可追踪、可恢复的多阶段工作流，而不是让一次 LLM 调用承担所有逻辑。
实现方式：链路可以描述为：用户输入 topic/platform/style/wordRange/userDescription -> ArticleController 创建任务和 SSE 连接 -> ArticleAsyncService 进入 @Async("articleExecutor") 异步执行 -> ArticleAgentOrchestrator 按阶段编排 -> ArticleAgent 生成标题/大纲/正文 -> ImageAgent 规划配图 -> ImageToolExecutor 调用图片工具 -> ReviewAgent 做文本和图片评审 -> ContentMergerAgent 合成 Markdown 图文 -> ArticleService 持久化 MySQL -> SseEmitterManager 推送前端展示。
解决的问题：创作任务耗时长、模型输出不稳定、图片服务可能失败、用户需要看到阶段进度，所以需要把链路拆成任务状态、Agent 输出、工具执行和评审结果。
面试表达话术：我的系统核心不是单次 prompt，而是“异步任务 + Orchestrator + 多 Agent + 工具执行 + Review + SSE”的工作流。每一步都有明确输入输出和状态落库，便于解释结果是怎么来的。
可能追问：如果问最核心的类，可以回答 ArticleAsyncService 负责异步任务入口，ArticleAgentOrchestrator 负责编排，ArticleAgent/ImageAgent/ReviewAgent/ImageToolExecutor/ContentMergerAgent 分别承担生成、规划、评审、工具执行和合成。"""
    ),
    (
        "【系统设计与可解释性】Controller、Service、Orchestrator、Agent、Tool、Review、Storage 的职责边界是什么？",
        """设计目的：避免所有逻辑堆在 Controller 或 Agent 里，保证系统能扩展、能排查、能替换模型或工具。
实现方式：Controller 负责参数校验、鉴权入口和触发任务；ArticleAsyncService 负责异步阶段执行、SSE 消息转换和状态更新；Orchestrator 负责决定阶段顺序、并行分支和 fallback；Agent 负责模型侧生成或规划；Tool 层负责把结构化需求转换为第三方图片服务调用；ReviewAgent 负责质量评估和重规划信号；Storage 通过 ArticleService、AgentLogService 保存文章、阶段、日志和执行指标。
解决的问题：边界清晰后，模型调用、工具调用、状态持久化、用户交互不会互相污染。例如图片服务失败只影响 Tool Executor 的重试和降级，不会迫使 Controller 理解图片生成细节。
面试表达话术：我把“请求入口、任务编排、智能体能力、外部工具、质量控制、持久化”拆成六层，这样每层只负责一类变化。后续换模型、加工具或加评测，不需要重写主流程。
可能追问：如果问 Service 和 Orchestrator 区别，可以说 Service 更偏应用服务和事务状态，Orchestrator 更偏 AI Workflow 的阶段编排和分支控制。"""
    ),
    (
        "【系统设计与可解释性】为什么这不是简单的 LLM API 包装？",
        """设计目的：解决真实 AI 应用里的不可控问题，包括长任务、阶段确认、结构化输出失败、工具失败、质量不稳定和可观测性不足。
实现方式：项目中不是一次 call 返回文章，而是标题、大纲、正文、配图规划、图片执行、评审、合成多个节点。每个节点有结构化输出校验，ReviewAgent 提供质量门禁，ImageToolExecutor 有重试、重规划和降级，ArticleAsyncService 用 SSE 推送阶段进度，AgentExecutionAspect 记录每个 Agent 的耗时、状态、输入摘要、输出摘要和异常。
解决的问题：LLM API 包装只能回答“模型说了什么”，但这个项目能回答“谁在什么时候生成了什么、为什么进入下一步、失败时怎么处理”。
面试表达话术：我更愿意把它定义成工程化 Agent Workflow。LLM 是能力内核，但系统价值在于编排、校验、工具调度、评审反馈和可观测闭环。
可能追问：如果被问 Agent 自主性不足，要承认当前主流程是受控 Workflow，但在配图工具选择、失败重规划、Review 反馈修正上体现了局部自主决策。"""
    ),
    (
        "【系统设计与可解释性】为什么拆成多个 Agent，而不是一个大 Prompt？",
        """设计目的：自媒体图文创作包含选题表达、结构规划、正文展开、图片规划、图片质量判断等异质任务，一个大 Prompt 会导致上下文过长、职责混杂、输出难解析。
实现方式：ArticleAgent 负责标题/大纲/正文文本生成，ImageAgent 负责配图需求和单图重规划，ReviewAgent 负责质量评审，ImageToolExecutor 负责工具执行，ContentMergerAgent 负责图文合成。每个 Agent 的输入输出结构不同，可以分别设计 Prompt、JSON schema、业务校验和日志。
解决的问题：拆分后可以在标题、大纲阶段引入 Human-in-the-loop，让用户确认后再进入下一阶段；配图失败也能只重规划单图，不需要重跑整篇文章。
面试表达话术：我拆 Agent 的原则不是为了堆概念，而是按照“职责差异、输入输出差异、失败恢复粒度”来拆。这样可维护性和可解释性都比大 Prompt 更好。
可能追问：如果问缺点，可以说多 Agent 会增加调用次数和延迟，所以我只在需要质量门禁和工具分支的地方拆分，没有把每个小动作都做成 Agent。"""
    ),
    (
        "【系统设计与可解释性】每个 Agent 的职责边界是什么？",
        """设计目的：让每个 Agent 只对自己的阶段质量负责，减少跨阶段耦合。
实现方式：ArticleAgent 负责文本侧生成，包括 generateTitles、generateOutline、generateContent；ImageAgent 负责从大纲或正文生成 ImageRequirement，并在工具失败或评审不通过时 replanSingleImage；ReviewAgent 负责标题、大纲、正文、配图计划、图片结果的评分和建议；ImageToolExecutor 虽然实现为执行器而不是纯生成 Agent，但在工作流里承担工具执行节点；ContentMergerAgent 负责插入占位符和把图片 URL 合并到 Markdown。
解决的问题：职责边界明确后，正文质量不好找 ArticleAgent 和 ReviewAgent，图片不匹配找 ImageAgent/ImageToolExecutor/ReviewAgent，合成位置不对找 ContentMergerAgent。
面试表达话术：我把生成、规划、执行、评审、合成拆开，是为了让每个节点可替换、可单测、可观测。比如图片工具换成 MCP 或其他服务时，不需要改正文生成逻辑。
可能追问：如果问 Agent 数量是否过多，可以说当前数量围绕核心业务阶段，属于中等粒度拆分，重点是边界清晰而不是数量越多越好。"""
    ),
    (
        "【系统设计与可解释性】多 Agent 协同中如何传递上下文？",
        """设计目的：保证标题、正文和配图不是各说各话，而是沿着同一份创作意图递进。
实现方式：系统用 ArticleState 承载任务状态，用 ArticleContext.fromState(state) 给 Agent 提供上下文。用户输入的 topic、platform、style、wordRange、userDescription 会贯穿后续阶段；标题确认后写入 state.title；大纲生成后写入 state.outline；正文基于 title 和 outline；配图规划基于 outline 或 content；图片执行结果写回 state.images 和 imageExecutionTraces。
解决的问题：上下文传递避免了风格漂移，也让每个阶段能复用前序产物。例如配图需求包含 sectionTitle、position、type、reason、keywords/prompt，让图片和正文结构对齐。
面试表达话术：我没有让每个 Agent 独立工作，而是用 ArticleState 作为中心状态对象，Orchestrator 负责把前一阶段的结果转成后一阶段的输入。
可能追问：如果问并发下 state 是否安全，可以说正文和配图并行时会复制一份 imageState 做图片规划，结束后再把 imageRequirements、images、trace 等字段合并回主 state，减少并发写冲突。"""
    ),
    (
        "【系统设计与可解释性】Orchestrator 的作用是什么？",
        """设计目的：把业务阶段的执行顺序、并行分支、异常处理和降级策略集中管理，避免 Agent 之间互相直接调用形成混乱依赖。
实现方式：ArticleAgentOrchestrator 提供 executePhase1_GenerateTitles、executePhase2_GenerateOutline、executePhase3_GenerateContent。Phase1 生成标题并通知完成；Phase2 设置 StreamHandlerContext，生成大纲并流式推送；Phase3 通过 CompletableFuture 把正文生成和基于大纲的配图分支并行执行，之后进行图片 fallback、占位符插入和最终 Markdown 合成。
解决的问题：Orchestrator 让流程具备可控性，例如图片大纲规划失败不会直接让整篇文章失败，而是返回空图片结果，再走基于正文的 fallback 规划；如果仍失败，则保留纯文本 fullContent。
面试表达话术：Orchestrator 是 AI Workflow 的控制平面，Agent 是能力节点。它不负责具体生成内容，但负责什么时候调用谁、失败后走哪条分支、怎样把结果合并。
可能追问：如果问为什么不让 Agent 自己调度 Agent，可以说当前场景更重视稳定交付和用户确认，受控 Orchestrator 比完全自治更适合生产化 MVP。"""
    ),
    (
        "【系统设计与可解释性】当前设计更接近 Pipeline、Workflow 还是 Autonomous Agent？",
        """设计目的：客观定位系统能力，避免面试中把固定流程硬吹成完全自主 Agent。
实现方式：当前主链路更接近 Workflow：它有固定阶段、明确状态流转、Human-in-the-loop 确认标题和大纲，也有局部分支和并行。它不属于完全 Autonomous Agent，因为系统没有让模型自主规划任意步骤、长期记忆和开放工具探索。
解决的问题：Workflow 定位更符合内容生成产品对稳定性和可控性的要求。标题、大纲、正文、配图这些阶段不能随意跳过，否则用户体验和数据持久化会变复杂。
面试表达话术：我会说它是“受控 Workflow + 局部 Agentic 能力”。主流程是确定性的，保证可交付；在配图工具选择、图片失败重规划、Review 反馈修正上体现一定自主性。
可能追问：如果问如何演进到 Autonomous Agent，可以补充引入 PlannerAgent、动态 DAG、工具注册协议、记忆和任务终止条件，但会先通过评测保证质量。"""
    ),
    (
        "【系统设计与可解释性】如果面试官问“你的 Agent 自主性体现在哪里”，怎么回答？",
        """设计目的：既承认系统不是完全自治，又说明项目确实具备 Agentic 设计。
实现方式：自主性主要体现在三处：第一，ImageAgent 会根据文章主题、标题、章节和允许的工具集合生成结构化配图需求，动态选择图库检索、文生图、流程图、图标等方式；第二，ImageToolExecutor 在工具失败或 ReviewAgent 不通过时，会把 toolError、imageResult、reviewResult 封装成 ImageObservation，让 ImageAgent 对单张图重规划；第三，ReviewAgent 通过 score、approved、nextAction、revisionAdvice 决定当前阶段是通过、重写、重规划还是 fallback。
解决的问题：这些机制让系统不是固定调用一个 API，而是能根据中间观察结果调整后续动作。
面试表达话术：我的项目自主性不是开放世界的自主规划，而是业务边界内的自主决策：工具选择、失败重规划和质量反馈闭环。这样更适合工程落地，因为可解释、可控、可回滚。
可能追问：如果问不足，可以说缺少全局 Planner 和动态任务图，这是后续可以用 StateGraph/LangGraph 思路增强的方向。"""
    ),
    (
        "【系统设计与可解释性】为什么大模型结构化输出容易失败？",
        """设计目的：解释为什么需要 JsonStructuredOutputService，而不是简单相信 Prompt 里的“请输出 JSON”。
实现方式：模型常见失败包括：JSON 外包 Markdown 代码块，前后夹杂解释文本；对象和数组根类型不一致；字段缺失或空值；枚举值拼错；数组数量不满足业务要求；大纲编号不连续；图片需求里 AI 生图缺 prompt、图库检索缺 keywords。
解决的问题：这些失败如果不拦截，会在后续 Agent 或工具调用处放大。例如 imageSource 不合法会导致策略模式找不到服务，placeholderId 不存在会导致图文合成错位。
面试表达话术：结构化输出失败不是解析问题本身，而是 Agent Workflow 的稳定性问题。一个阶段输出不稳定，会污染后续所有阶段。
可能追问：如果问为什么不只用模型 JSON mode，可以说 JSON mode 只能提高语法合法性，不能保证业务规则，例如标题数量、章节连续性、图片方法枚举和 prompt/keywords 条件。"""
    ),
    (
        "【系统设计与可解释性】JsonStructuredOutputService 具体解决了哪些问题？",
        """设计目的：为所有 Agent 输出提供统一的“提取、解析、校验、重试、指标记录”入口。
实现方式：JsonStructuredOutputService 的 parse 方法支持 Class 和 TypeToken 两种目标类型，默认 DEFAULT_MAX_RETRIES=1。内部先 extractJson，去掉单个 ```json 代码块；再根据 StructuredOutputTypeEnum 的 RootType 从第一个 { 或 [ 开始做括号平衡提取；然后用 Gson 解析、normalizeRoot、validateRootType；最后按 TITLE_OPTIONS、OUTLINE_RESULT、IMAGE_PLAN、OUTLINE_IMAGE_PLAN、REVIEW_RESULT、IMAGE_REVIEW_RESULT 做业务校验。
解决的问题：它把“能解析 JSON”和“能进入下一阶段”区分开。只有语法、根类型、业务规则都通过，结果才返回给 Agent 流程。
面试表达话术：我把结构化输出做成了统一服务，而不是散落在每个 Agent 里。这样规则集中、日志统一，也能把 parseSuccess、schemaValid、businessValid、repairCount、retryCount 写入 StructuredOutputTraceContext。
可能追问：如果问收益，可以说这让异常更早暴露，避免错误传到工具层才失败，同时可以统计哪个输出类型最容易失败。"""
    ),
    (
        "【系统设计与可解释性】JSON 提取、括号平衡、字段校验、枚举校验、失败重试分别解决什么问题？",
        """设计目的：把模型输出不稳定拆成不同层次处理，不能用一个 try-catch 糊住。
实现方式：JSON 提取解决模型在 JSON 前后加说明文字的问题；去 fence 解决 ```json 包裹问题；括号平衡解决内容中存在多余说明但 JSON 本体完整的问题，而且会处理字符串和转义，避免遇到字符串里的 } 就截断；字段和根类型校验解决对象/数组不匹配、必填字段缺失问题；枚举校验通过 ImageMethodEnum.getByValue 拦截非法 imageSource；失败重试通过 retrySupplier 重新调用模型一次。
解决的问题：多层校验可以把错误定位为语法、schema 或业务规则，便于排查。
面试表达话术：我的校验不是为了追求格式洁癖，而是为了保护后续 Workflow。比如 AI 生图必须有 prompt，图库检索必须有 keywords，否则工具层无法稳定执行。
可能追问：如果问重试为什么只默认 1 次，可以说内容生成成本较高，过多重试会增加延迟和费用；第一版采用一次纠错重试，后续可以基于错误类型做自适应重试。"""
    ),
    (
        "【系统设计与可解释性】为什么不用简单正则解析 JSON？",
        """设计目的：说明技术选型不是复杂化，而是为了处理真实模型输出中的边界情况。
实现方式：简单正则很难可靠处理嵌套对象、数组、字符串里的花括号、转义字符和多段 JSON。项目里的 extractBalancedJson 从预期根类型的第一个括号开始，维护 depth、inString、escape 状态，只有在非字符串上下文 depth 回到 0 时才截取完整 JSON。
解决的问题：正则可能把 JSON 截断或多截，导致偶发解析失败；括号平衡更适合处理模型输出里的嵌套结构，例如 imageRequirements 数组中每个对象又包含 prompt、reason、metadata。
面试表达话术：我没有用正则是因为这里解析的是模型生成的半可信结构化文本。正则适合简单模式匹配，不适合可靠提取嵌套 JSON。
可能追问：如果问更标准方案，可以说未来可以优先使用模型原生 structured output / function calling，再保留 JsonStructuredOutputService 作为业务校验层。"""
    ),
    (
        "【系统设计与可解释性】当前图片工具体系如何设计？",
        """设计目的：支持不同图片来源，覆盖图库检索、AI 生图、流程图、图标、兜底图等场景，同时对上层暴露统一调用方式。
实现方式：ImageMethodEnum 定义 PEXELS、CHINA_IMAGE_SEARCH、NANO_BANANA、MERMAID、GRAPHVIZ、ICONIFY、EMOJI_PACK、SVG_DIAGRAM、QWEN_IMAGE、PICSUM 等方法，并标记 AI 生成、fallback 等元信息。各图片服务实现 ImageSearchService，ImageServiceStrategy 在 @PostConstruct 中把所有服务注册到 EnumMap<ImageMethodEnum, ImageSearchService>。ImageGenerationTool 对接策略层，ImageToolExecutor 并行执行每个 ImageRequirement。
解决的问题：新增图片工具时只需要增加 ImageSearchService 实现和枚举配置，上层 Agent 不需要知道具体 API 细节。
面试表达话术：这套设计把“模型决定需要什么图”和“工程系统怎么拿到图”解耦。Agent 输出 imageSource、keywords、prompt，执行层按枚举选择具体服务。
可能追问：如果问 Tool Calling 体现在哪里，可以说模型不是直接调用 API，而是先生成结构化工具调用意图，执行器再把意图映射到真实工具。"""
    ),
    (
        "【系统设计与可解释性】ImageServiceStrategy、Tool Executor、图片服务之间边界是什么？",
        """设计目的：区分工具路由、工具编排和具体服务实现。
实现方式：ImageServiceStrategy 负责根据 imageSource 解析 ImageMethodEnum、选择 ImageSearchService、判断可用性、调用服务并上传 COS、失败时 fallback。ImageToolExecutor 负责多图并行、单图重试、ReviewAgent 评审、失败重规划、选择最佳候选和推送 IMAGE_COMPLETE。具体图片服务如 PexelsService、QwenImageService、MermaidService、GraphvizService 只负责某一种来源的数据获取或生成。
解决的问题：Strategy 解决“调用哪个服务”，Executor 解决“整个工具节点怎么执行和恢复”，具体服务解决“怎么调用第三方 API”。
面试表达话术：我把工具调用拆成三层：服务实现、策略路由、执行编排。这样工具失败时，系统能在执行层重试和重规划，在策略层降级，在服务层隔离第三方 API 差异。
可能追问：如果问上传 COS 在哪里，可以说 ImageServiceStrategy 的 getImageAndUpload 统一把 ImageData 上传到 COS，保证最终文章引用的是稳定 URL。"""
    ),
    (
        "【系统设计与可解释性】为什么使用策略模式做图片工具调度？",
        """设计目的：图片工具类型多，而且差异很大，使用 if-else 会让工具选择逻辑难维护。
实现方式：每个图片服务实现统一的 ImageSearchService 接口，通过 getMethod 返回自己的 ImageMethodEnum。ImageServiceStrategy 启动时自动注入 List<ImageSearchService> 并注册到 EnumMap。执行时只根据 imageSource 找对应服务，不需要硬编码服务类。
解决的问题：新增 Qwen 生图、SVG Diagram、Iconify 图标等工具时，扩展点清晰，不会影响现有工具。服务不可用时也可以统一走 fallback。
面试表达话术：策略模式的价值是把工具扩展从“改主流程”变成“加实现类”。对于 Tool Calling 场景，工具集合一定会变，所以路由层必须可扩展。
可能追问：如果问是否可以用 Spring Bean 名称路由，可以说可以，但枚举还能承载是否 AI 生成、是否 fallback、默认方法等业务元数据，更适合当前场景。"""
    ),
    (
        "【系统设计与可解释性】Tool Calling 失败时如何重试、降级、兜底？",
        """设计目的：图片生成和检索受外部服务、网络、审核、渲染语法影响，必须保证文章最终可交付。
实现方式：ImageToolExecutor 对每个 ImageRequirement 先 generateWithOneRetry，同参数失败会重试一次；若工具失败且未超过 MAX_REPLAN_ATTEMPTS=2，会构造 ImageObservation，把 toolError 交给 ImageAgent.replanSingleImage；若生成成功但 ReviewAgent 图片评审不通过，也会基于 imageResult/reviewResult 重规划。达到上限后使用 bestImage，完全没有候选则调用 fallbackImage，最终走 ImageMethodEnum.getFallbackMethod，当前兜底为 PICSUM。Mermaid/Graphviz 渲染失败时还会提示在两种图表语法之间切换。
解决的问题：这套机制避免单个图片 API 失败拖垮整篇文章，并且尽量保留语义相关的最佳候选。
面试表达话术：我的失败处理不是简单 catch 后返回默认图，而是“同参数重试 -> 带错误观察的单图重规划 -> 评审选优 -> fallback 兜底”的分层恢复。
可能追问：如果问是否会无限重试，可以说不会，MAX_REPLAN_ATTEMPTS 明确限制，保证成本和延迟可控。"""
    ),
    (
        "【系统设计与可解释性】动态调度图库检索、文生图、流程图、图标生成时怎么解释？",
        """设计目的：不同内容需要不同图片表达方式，比如概念解释适合流程图，生活场景适合图库，抽象封面适合文生图，功能点适合图标。
实现方式：ImageAgent 输出 ImageRequirement，其中包含 position、sectionTitle、type、reason、imageSource、keywords、prompt、placeholderId。JsonStructuredOutputService 会校验 imageSource 必须属于 ImageMethodEnum，并要求 AI 生图方法必须有 prompt，非 AI 检索方法必须有 keywords。ImageToolExecutor 再按 imageSource 调用对应工具。
解决的问题：工具选择从固定规则变成由内容语义驱动，同时又用枚举和业务校验限制模型不能乱选。
面试表达话术：这是受控的动态工具调度。Agent 根据章节语义决定工具类型，工程侧用枚举、字段校验和策略模式把决策落到真实工具。
可能追问：如果问和普通第三方 API 调用区别，可以说普通 API 调用是开发者写死调用哪个接口；这里是模型先产出工具意图，执行层再校验、路由、重试、评审和降级。"""
    ),
    (
        "【系统设计与可解释性】ReviewAgent 的作用是什么？",
        """设计目的：给多阶段生成加质量门禁，避免模型输出直接进入下一步。
实现方式：ReviewAgent 提供 reviewTitles、reviewOutline、reviewContent、reviewImagePlan、reviewImageResult。文本评审使用 REVIEW_PROMPT，结合 stageName、styleRubric、stageRubric、contentProfile 和待评内容；图片评审会尝试下载图片，构造 base64 多模态输入调用 DashScope 视觉模型，失败时降级为文本评审。评审结果统一解析为 ReviewResult 或 ImageReviewResult。
解决的问题：它让系统能判断生成结果是否符合主题、平台风格、阶段目标和图片需求，而不是只依赖生成 Agent 自我约束。
面试表达话术：ReviewAgent 相当于工作流里的质量控制节点，它不负责生产内容，而是负责把结果转成可执行的质量信号，比如 approved、score、problems、suggestions、nextAction、revisionAdvice。
可能追问：如果问是否会增加成本，可以说会增加一次模型调用，但换来阶段质量门禁和可解释反馈，适合内容生成这种主观质量要求高的场景。"""
    ),
    (
        "【系统设计与可解释性】ReviewAgent 和普通生成 Agent 的区别是什么？",
        """设计目的：区分 Generator 和 Critic 的角色，避免同一个 Agent 既当运动员又当裁判。
实现方式：ArticleAgent 和 ImageAgent 主要根据上下文生成候选内容或规划；ReviewAgent 用低温度 temperature=0.0 的评审模型，根据 rubric 输出结构化评分、问题、建议和下一步动作。文本评审还会校验 dimensionScores，包括 commonBaseline、styleFit、stageFit，并把分数归一到总分。
解决的问题：生成 Agent 关注“产出”，ReviewAgent 关注“是否符合标准”。这让质量问题可以被记录、解释和触发重写/重规划。
面试表达话术：ReviewAgent 更像 Critic，而不是另一个 Generator。它的输出不是给用户看的正文，而是给工作流使用的质量决策。
可能追问：如果问能否完全相信 ReviewAgent，要回答不能完全相信，所以需要结构化 rubric、低温度、日志追踪、人工抽检和离线评测共同提升可信度。"""
    ),
    (
        "【系统设计与可解释性】ReviewAgent 如何评估标题、大纲、正文和配图？",
        """设计目的：不同阶段的质量标准不同，评审不能只用一个泛化标准。
实现方式：标题评估吸引力、差异化、风格匹配和是否承接正文方向；大纲评估结构覆盖、章节边界、可写性和风格路径；正文评估大纲覆盖、风格一致、信息/情绪/转化目标达成；配图计划评估位置、工具选择、关键词或 prompt 质量、占位符匹配；图片结果评估图片是否符合章节语义、是否可用、是否需要重规划或 fallback。
解决的问题：分阶段 rubric 可以让 ReviewAgent 的输出更可解释，也能让失败原因定位到具体阶段。
面试表达话术：我不是让模型泛泛说“好不好”，而是给每个阶段不同 rubric，并要求输出 score、approved、problems、suggestions、nextAction，这些字段会被结构化解析和日志记录。
可能追问：如果问图片怎么评审，可以说优先使用视觉模型查看图片字节；如果图片下载、模型或 URL 有问题，则降级为文本评审，保证流程不中断。"""
    ),
    (
        "【系统设计与可解释性】ReviewAgent 是否可以类比为 Critic / Reflection / Self-Refine？",
        """设计目的：把项目设计和通用 Agent 范式关联起来，但不夸大。
实现方式：它可以类比 Critic，因为它对 Generator 输出做评分和问题诊断；可以类比 Reflection，因为它把问题、建议、revisionAdvice 反馈给下一轮生成或重规划；可以部分类比 Self-Refine，因为图片分支中评审不通过会触发 ImageAgent.replanSingleImage。
解决的问题：这说明项目不是单向生成，而是有反馈闭环。
面试表达话术：我会说它借鉴了 Critic/Reflection/Self-Refine 思路，但实现上是受控版本。当前主要在图片结果和阶段质量门禁中使用反馈，不是无限自我迭代。
可能追问：如果问为什么不无限迭代，可以说生产系统要控制成本、延迟和不确定性，所以设置分数阈值、重试上限和 fallback。"""
    ),
    (
        "【系统设计与可解释性】LLM 评审会不会自嗨，如何保证可信度？",
        """设计目的：正面回应 LLM-as-Judge 的局限，体现工程判断。
实现方式：当前通过几层机制降低风险：评审 Prompt 使用明确 rubric；temperature=0.0 降低随机性；输出必须经过 JsonStructuredOutputService 校验；文本评审要求 dimensionScores 且分数与维度和一致；图片评审优先用视觉模型真实读取图片，失败才降级文本；所有评审结果写入 SSE 和 AgentLog，便于人工抽查。
解决的问题：这些措施不能让评审绝对客观，但能让评审过程可重复、可解释、可追踪。
面试表达话术：我不会说 LLM 评审完全可信。我的设计是把它作为自动化质量门禁和召回问题的工具，再结合人工抽检、离线样本集和业务指标验证。
可能追问：如果问最终评估方案，可以说会设计人工评分集、LLM-as-Judge 双模型交叉评审、链路通过率、重试率、结构化解析失败率、平均耗时和用户采纳率。"""
    ),
    (
        "【系统设计与可解释性】请设计一套最终结果评估方案。",
        """设计目的：把主观内容质量和工程稳定性都纳入评估，避免只看“能不能生成”。
实现方式：人工评估：抽样文章，从主题相关性、结构完整性、平台风格、可读性、配图匹配度打分。LLM-as-Judge：使用固定 rubric 和不同模型交叉评审，输出 win-rate 和问题类型分布。链路指标：标题/大纲/正文/配图计划 Review 通过率，结构化输出解析失败率，图片重规划次数，fallback 使用率。工程指标：P50/P95 生成耗时，SSE 首包时间，异常率，第三方工具成功率，AgentLog 中各阶段耗时。
解决的问题：人工评估看内容真实质量，LLM-as-Judge 提升评测规模，链路指标定位问题阶段，工程指标保证用户体验。
面试表达话术：内容类 AI 应用不能只做单元测试，我会把评估分为“质量结果”和“执行过程”两类，既看最终文章，也看中间链路是否稳定。
可能追问：如果问怎么落地，可以先做 30-50 条固定主题 Benchmark，每次改 Prompt 或模型后批量跑一遍，比较平均分、失败率和耗时。"""
    ),
    (
        "【系统设计与可解释性】为什么创作任务需要异步化？",
        """设计目的：图文创作包含多次大模型调用和图片工具调用，同步 HTTP 请求容易超时，用户也无法感知进度。
实现方式：ArticleAsyncService 的 executePhase1、executePhase2、executePhase3 使用 @Async("articleExecutor")，Controller 触发任务后由后台线程池执行。前端通过 SSE 接收阶段事件，后端任务完成后更新 ArticleStatus 和 ArticlePhase。
解决的问题：异步化让用户不用阻塞等待，也让服务端可以把长任务拆成阶段状态，失败时能记录到数据库并推送 ERROR。
面试表达话术：这里异步不是为了炫技，而是因为 LLM + 图片生成天然是长任务。同步接口只能返回最终结果，异步任务可以返回过程、状态和可恢复性。
可能追问：如果问线程池参数，可以说 articleExecutor corePoolSize=5、maxPoolSize=10、queueCapacity=100、threadNamePrefix=article-async-、拒绝策略 CallerRunsPolicy，关闭时等待任务完成最多 60 秒。"""
    ),
    (
        "【系统设计与可解释性】@Async、线程池、CompletableFuture 分别解决什么问题？",
        """设计目的：区分任务级异步和阶段内并行。
实现方式：@Async("articleExecutor") 解决请求线程和长任务解耦，把文章阶段放到自定义线程池执行；线程池控制系统并发度、队列和拒绝策略，避免无限创建线程；CompletableFuture 用于 Phase3 内部并行，把正文生成 contentFuture 和配图分支 imageFuture 同时启动，并在 join 后合并结果。ImageToolExecutor 也用 CompletableFuture 对多张图片并行执行。
解决的问题：@Async 提升接口响应性，线程池保护服务资源，CompletableFuture 缩短可并行阶段的总耗时。
面试表达话术：我把异步分两层：外层是任务异步，保证 HTTP 不阻塞；内层是业务并行，利用正文生成和配图规划互不完全依赖的特点减少等待。
可能追问：如果问不足，可以说 Orchestrator 里的 CompletableFuture.runAsync/supplyAsync 当前未显式传入 articleExecutor，会默认使用 commonPool，后续可改为注入专用 Executor，便于资源隔离和监控。"""
    ),
    (
        "【系统设计与可解释性】为什么正文生成和配图分支可以并行？",
        """设计目的：减少总生成耗时，同时不牺牲内容一致性。
实现方式：用户确认大纲后，正文生成依赖 title + outline；配图规划也可以先基于 outline 生成章节级图片需求。所以 ArticleAgentOrchestrator 在 Phase3 中复制 imageState，contentFuture 生成正文，imageFuture 调用 runOutlineImageBranch 做配图规划和图片执行。等两个 Future 都完成后，再把图片需求和图片结果合并回主 state，并用 ContentMergerAgent 插入占位符和合成 fullContent。
解决的问题：如果等正文全部生成后再开始配图，会把图片工具耗时串到总链路后面；基于大纲提前配图可以覆盖大部分章节语义。
面试表达话术：正文和配图不是完全无依赖，但它们都依赖已经确认的大纲，因此可以把大纲作为并行分支的共同输入。若大纲配图失败，系统再 fallback 到基于正文的配图规划。
可能追问：如果问一致性风险，可以说最终合成前会用 ReviewAgent 和 ContentMergerAgent 校验图片位置和语义，必要时重规划单图。"""
    ),
    (
        "【系统设计与可解释性】SSE 解决了什么用户体验问题？为什么不用 WebSocket？",
        """设计目的：让长任务对用户透明，实时展示标题生成、大纲流式输出、正文流式输出、图片完成、合成完成等阶段。
实现方式：后端用 SseEmitterManager 管理连接，ArticleAsyncService 把 AGENT2_STREAMING、AGENT3_STREAMING、IMAGE_COMPLETE、REVIEW_COMPLETE、MERGE_COMPLETE 等消息转换成 JSON 推送前端。前端只需要订阅服务端事件并更新页面状态。
解决的问题：用户不用盯着加载页等待几十秒，可以看到系统正在生成哪一段、哪张图已完成、评审是否通过。
面试表达话术：我选择 SSE 是因为这个场景主要是服务端向前端单向推送进度，客户端不需要高频双向通信。SSE 基于 HTTP，协议简单，断线重连和代理兼容性更好。WebSocket 更适合实时协作、聊天、游戏这类双向高频场景。
可能追问：如果问未来什么时候用 WebSocket，可以说如果加入多人协作编辑、实时取消/暂停/改写指令，再考虑 WebSocket 或 SSE + 控制接口组合。"""
    ),
    (
        "【系统设计与可解释性】如何解释“整体生成耗时降低约 15 秒”的来源？",
        """设计目的：让性能收益有可解释来源，而不是凭空报数字。
实现方式：原本串行链路是正文生成完成后，再配图规划、图片工具调用和图片评审；现在 Phase3 在大纲确认后同时启动正文生成和基于大纲的图片分支。节省的时间主要来自正文生成耗时与图片规划/部分图片执行耗时的重叠。多图片执行也通过 CompletableFuture 并行处理，避免每张图串行等待。
解决的问题：对用户感知来说，最慢的是最终文章完成时间；并行后总耗时接近 max(正文分支耗时, 图片分支耗时) + 合成时间，而不是两者相加。
面试表达话术：15 秒不是理论值，而是来自把正文和配图分支重叠执行后的经验量级。准确表达可以说“在典型任务下减少约 15 秒，具体取决于图片数量、第三方工具响应和模型耗时”。
可能追问：如果问怎么证明，可以说应该用 AgentLog 的 durationMs 按阶段统计串行版和并行版 P50/P95，并记录图片数量作为分组变量。"""
    ),
    (
        "【系统设计与可解释性】为什么多 Agent 系统需要链路追踪？",
        """设计目的：多 Agent 问题很难只从最终失败看出原因，需要知道每个节点的输入、输出、耗时和异常。
实现方式：项目定义 @AgentExecution 注解，在 Orchestrator、ReviewAgent、ImageToolExecutor 等关键方法上标注 agentName、description、phase、retryCount。AgentExecutionAspect 用 AOP around 拦截方法，执行前记录 startTime、taskId、phase、inputData，执行后记录 status、durationMs、outputData，异常时记录 errorMessage，最后异步保存 AgentLog。
解决的问题：当用户说“图片不对”或“生成失败”时，可以从 taskId 查到具体卡在 IMAGE_EXECUTING、IMAGE_REVIEWING 还是 CONTENT_GENERATING。
面试表达话术：Agent 系统不能只看应用日志，因为一个用户任务内部有多个模型和工具节点。我用注解 + AOP 做任务级 trace，把每个 Agent 节点变成可观测单元。
可能追问：如果问 traceId 怎么设计，可以说当前 traceId 使用 taskId，适合同一文章任务内串联日志；后续可以接 OpenTelemetry 生成标准 span。"""
    ),
    (
        "【系统设计与可解释性】@AgentExecution 和 AOP 切面如何工作？",
        """设计目的：让日志采集对业务代码低侵入，避免每个 Agent 方法手写重复日志。
实现方式：@AgentExecution 标注方法后，AgentExecutionAspect 的 @Around("@annotation(agentExecution)") 会拦截调用。切面从参数中提取 taskId，支持 ArticleState、ArticleContext、ImageObservation、OverAllState，也会提取 inputData 和 prompt 方法名。执行 pjp.proceed 后记录成功状态和输出摘要；catch Throwable 时记录失败状态和错误信息；finally 中把 StructuredOutputTraceContext.snapshot 放到 metadata，并调用 agentLogService.saveLogAsync 异步保存。
解决的问题：统一日志格式，减少漏记和字段不一致问题。
面试表达话术：这个设计把可观测能力做成横切关注点。Agent 只关心业务逻辑，日志切面负责采集执行链路、结构化输出指标和异常。
可能追问：如果问 input/output 会不会太大，可以说切面只记录摘要和关键字段，例如 taskId、mainTitle、listSize、ReviewResult，避免把完整正文或大图数据写入日志。"""
    ),
    (
        "【系统设计与可解释性】采集 Agent 名称、任务 ID、阶段、输入摘要、输出摘要、耗时、异常有什么价值？",
        """设计目的：把日志从“出错了”提升为“哪个任务的哪个节点因为什么出错，影响多大”。
实现方式：AgentLog 中 taskId/traceId 用于串联单个用户任务；phase 用于按阶段聚合；agentName 对应具体 Agent 方法；inputData/outputData 帮助复现上下文；durationMs 用于性能分析；errorMessage 用于失败归因；metadata 中包含 structuredOutputMetrics，可看到解析、schema、业务校验是否通过。
解决的问题：能定位问题，也能做运营和评测。例如发现 IMAGE_EXECUTING P95 特别高，就优化图片服务；发现 OUTLINE_RESULT businessValid 失败多，就优化大纲 Prompt 或校验规则。
面试表达话术：这些日志既服务线上排障，也服务后续 Agent 评测。没有日志，多 Agent 系统就是黑盒；有了 trace，才能做持续优化。
可能追问：如果问怎么知道哪个 Agent 出问题，可以回答用 taskId 查 AgentLog，按 startTime 或 phase 排序，看第一个 FAILED 或耗时异常的节点，再结合 metadata 的结构化输出指标定位是模型输出、业务校验还是工具失败。"""
    ),
    (
        "【系统设计与可解释性】当前项目中 RAG 的真实作用是什么？",
        """设计目的：客观说明当前实现状态，避免把规划中的能力说成已落地。
实现方式：当前仓库 README 和文档里有 RAG、PGVector、Query Rewrite、Multi Query 的设计说明，但代码主链路没有看到 RetrievalDecisionService、PGVector Retriever、Embedding 写入等已实现模块。因此面试中应该把 RAG 表达为“规划中的可选增强能力”或“简历里谨慎弱化”，不要说它已经深度接入主流程。
解决的问题：这能避免被面试官追问代码类名、表结构和召回逻辑时答不上来。
面试表达话术：当前项目的主亮点是 Multi-Agent Workflow、结构化输出、工具调度、Review 和可观测性。RAG 我设计为后续用于个人知识库和写作资料增强，不作为第一版主链路必经步骤。
可能追问：如果问为什么不先做 RAG，可以说第一版优先解决生成闭环和稳定性，RAG 会增加文档解析、向量库、召回质量和权限隔离复杂度，适合作为第二阶段增强。"""
    ),
    (
        "【系统设计与可解释性】RAG 如果接入内容生成，会解决什么问题？",
        """设计目的：说明 RAG 的业务价值，而不是只讲向量检索。
实现方式：在自媒体创作里，RAG 适合接入用户历史文章、品牌资料、人物设定、专业文档、产品说明和参考素材。生成标题、大纲、正文前，系统根据 topic 和用户意图检索相关 chunk，把高相关片段作为 retrievedContext 注入 Prompt。
解决的问题：RAG 可以解决私有知识、事实资料、风格复用和专业内容准确性问题。比如写品牌宣传文时，模型不能凭空编产品卖点，应该检索品牌资料后再写。
面试表达话术：我不会把 RAG 当成所有文章的默认步骤，而是作为可选知识增强。只有用户开启知识库或题目明显需要资料支撑时才检索，避免无效召回污染上下文。
可能追问：如果问 RAG 降低幻觉的原理，可以说它把模型回答从纯参数记忆转为“基于外部证据生成”，但最终表述仍由 LLM 生成，所以只能降低，不能完全消除幻觉。"""
    ),
    (
        "【系统设计与可解释性】Query Rewrite、Multi Query、PGVector 分别解决什么问题？",
        """设计目的：说明 RAG 子模块各自价值，避免只会背术语。
实现方式：Query Rewrite 把用户口语化选题改写成更适合检索的查询，例如提取产品名、场景、受众和内容目标；Multi Query 从多个角度生成检索查询，例如主题事实、风格样例、用户画像、竞品资料，提升召回覆盖；PGVector 用 PostgreSQL 向量扩展保存 chunk embedding，并按 userId/knowledgeBaseId 做过滤和相似度 topK 检索。
解决的问题：Rewrite 提升单次查询质量，Multi Query 降低漏召回，PGVector 解决向量存储和相似度检索。
面试表达话术：RAG 的难点不是“把文本向量化”，而是如何构造好查询、控制召回范围、过滤低质量 chunk，并把证据安全注入到 Agent 上下文。
可能追问：如果问为什么不用 Elasticsearch，可以说关键词检索适合精确匹配，向量检索适合语义相似；实际可做 hybrid search，用 BM25 + vector rerank。"""
    ),
    (
        "【系统设计与可解释性】RAG 如何降低幻觉？为什么不能完全消除？",
        """设计目的：展示对 RAG 边界的理解。
实现方式：RAG 通过检索外部知识，把事实、术语、产品参数、历史风格样例提供给模型，Prompt 中要求优先依据 retrievedContext。这样模型不用完全依赖训练参数，可以减少编造。
解决的问题：它能降低事实错误、风格不一致和资料遗漏，但不能完全消除幻觉，原因包括召回可能不相关、知识库本身可能过期或错误、chunk 被截断导致上下文缺失、模型可能错误综合证据、用户问题超出知识库覆盖。
面试表达话术：我会把 RAG 当作“证据增强机制”，不是事实正确性的绝对保证。生产上还要做相似度阈值、引用片段展示、低置信度拒答、人工审核和评测集。
可能追问：如果没有命中高质量知识，可以回答系统应该明确降级为普通生成，并在上下文里标记 no_reliable_context，避免模型假装引用资料。"""
    ),
    (
        "【系统设计与可解释性】RAG 和微调有什么区别？",
        """设计目的：解释技术选型边界。
实现方式：RAG 是推理时检索外部知识，把相关片段放进上下文；微调是训练或适配模型参数，让模型长期学会某种格式、风格或任务模式。
解决的问题：RAG 适合频繁变化、私有化、需要可追溯证据的知识；微调适合稳定的输出风格、领域表达习惯、固定任务格式。RAG 更新知识只需要更新库，微调更新成本更高。
面试表达话术：对这个项目来说，用户资料和创作素材变化频繁，所以优先 RAG；如果后续有大量高质量样本，希望模型稳定掌握平台文风或评审标准，可以考虑轻量微调或 LoRA。
可能追问：如果问二者能否结合，可以说可以，RAG 负责事实和资料，微调负责表达风格和任务服从。"""
    ),
    (
        "【系统设计与可解释性】48 小时内建议补哪些轻量 RAG 增强点？",
        """设计目的：给出有面试价值且可落地的增强路线。
实现方式：第一，做一个最小 KnowledgeBase/Chunk 表，可以先不用 PGVector，使用 MySQL 存文档和 chunk，模拟检索或接 Spring AI SimpleVectorStore；第二，接入 EmbeddingModel + PGVector 或本地向量存储，实现 topK 检索；第三，增加 RetrievalDecisionService，根据用户开关、知识库是否有内容、topic 是否命中关键词决定是否检索；第四，在 ArticleContext 增加 retrievedContext，并在 ArticleAgent 的标题/大纲/正文 Prompt 中注入；第五，在 AgentLog metadata 记录 retrievalQuery、hitCount、topScore、chunkIds。
解决的问题：这些点能形成完整的“上传资料 -> 检索 -> 注入 -> 可观测”的 RAG MVP。
面试表达话术：如果时间有限，我不会一上来做 Agentic RAG，而是先做可解释的 Deterministic RAG MVP。重点是能展示召回片段、相似度和是否参与生成。
可能追问：如果问 Query Rewrite 是否必做，可以说 48 小时内可以先做规则改写，后续再加 QueryRewriteAgent 和 Multi Query。"""
    ),
    (
        "【系统设计与可解释性】最适合写进简历的 5 个项目亮点是什么？",
        """设计目的：把项目价值压缩成能被技术面试官理解的工程亮点。
实现方式：第一，多 Agent Workflow：基于 ArticleAgentOrchestrator 编排标题、大纲、正文、配图规划、工具执行、Review 和合成。第二，结构化输出稳定性：JsonStructuredOutputService 做 JSON 提取、括号平衡、根类型校验、业务校验、重试和指标记录。第三，Tool Calling 工具体系：ImageMethodEnum + ImageServiceStrategy + ImageToolExecutor 支持多图片工具动态路由、并行执行、重规划和降级。第四，异步流式体验：@Async 自定义线程池 + CompletableFuture 并行分支 + SSE 阶段推送。第五，可观测与质量控制：ReviewAgent 质量门禁，@AgentExecution + AOP 记录 Agent 链路、耗时、异常和结构化输出指标。
解决的问题：这 5 点分别对应 Agent 架构、Workflow 编排、工程稳定性、工具调用、质量评估与可观测性。
面试表达话术：项目亮点不是“用了大模型”，而是把大模型应用做成了可控、可扩展、可观测的生产化工作流。
可能追问：如果问最能体现个人能力的一点，可以优先讲结构化输出 + Review + 工具降级，因为这最体现 AI 应用工程化。"""
    ),
    (
        "【系统设计与可解释性】面试高频：你项目里 Prompt Engineering 怎么做？",
        """设计目的：说明 Prompt 不是简单拼字符串，而是围绕阶段目标和可解析输出设计。
实现方式：Prompt 按 Agent 和阶段拆分，标题、大纲、正文、配图规划、评审各有不同约束。上下文注入 topic、platform、style、wordRange、userDescription、title、outline 等字段；输出端要求 JSON 结构，并由 JsonStructuredOutputService 兜底校验。ReviewPrompt 额外注入 styleRubric、stageRubric 和 contentProfile。
解决的问题：角色约束保证模型知道当前任务，参数注入保证风格一致，结构化约束保证后续流程能解析。
面试表达话术：我的 Prompt 设计重点是“阶段目标 + 上下文继承 + 输出契约”。Prompt 只是第一层约束，真正稳定性靠后面的结构化校验和 Review。
可能追问：如果问如何优化 Prompt，可以说通过 AgentLog 分析失败样本，针对高频 JSON 错误、低分评审项和工具失败原因迭代 Prompt。"""
    ),
    (
        "【系统设计与可解释性】面试高频：如果某个 Agent 输出质量差，你怎么定位和优化？",
        """设计目的：展示排障闭环。
实现方式：先按 taskId 查 AgentLog，找到低分 Review 或 FAILED 节点；看 metadata 中 structuredOutputMetrics，判断是 parse、schema、business 校验失败还是内容质量失败；再看 ReviewAgent 的 problems/suggestions 和阶段耗时；如果是 Prompt 问题，调整该阶段 Prompt 和 schema；如果是工具问题，查看 imageExecutionTraces 的 toolError、fallbackUsed、reviewScore。
解决的问题：避免凭感觉调 Prompt，而是用日志和评审结果定位。
面试表达话术：我会先定位问题类型，再决定优化手段。结构化失败调输出约束，质量低调 rubric 和 Prompt，工具失败调策略和 fallback，耗时高看并行和第三方 API。
可能追问：如果问有没有自动化评测，可以承认当前标准 Benchmark 还不完善，后续会补固定主题集和回归评测。"""
    ),
    (
        "【系统设计与可解释性】面试高频：如果标题、大纲、正文风格不一致怎么办？",
        """设计目的：说明多阶段一致性控制。
实现方式：系统用 ArticleState/ArticleContext 传递同一组 topic、platform、style、wordRange、userDescription。标题经过用户确认后成为后续大纲和正文的约束；大纲生成后成为正文和配图分支的共同输入；ReviewAgent 用 styleRubric 和 stageRubric 检查风格和阶段适配；结构化输出确保大纲章节连续、配图位置和章节对应。
解决的问题：风格一致性不是靠一次 Prompt，而是靠上下文递进、用户确认、结构化校验和 Review 共同控制。
面试表达话术：我把标题作为定调，大纲作为结构骨架，正文作为展开，配图服务章节语义。每一步都继承前一步结果，并经过 ReviewAgent 评估风格匹配。
可能追问：如果问是否能完全保证，可以说不能百分百，但能通过一致上下文和评审门禁显著降低漂移。"""
    ),
    (
        "【系统设计与可解释性】面试高频：项目最大的不足是什么？",
        """设计目的：客观呈现工程判断，避免硬吹。
实现方式：第一，当前主流程更接近固定 Workflow，不是完全自主 Agent；第二，RAG 主要在设计文档中，主链路尚未完整落地；第三，评审依赖 LLM-as-Judge，缺少系统化人工 Benchmark；第四，CompletableFuture 内部并行目前未统一接入自定义 Executor；第五，Python/LangGraph 生态覆盖不足，主要基于 Java/Spring AI Alibaba。
解决的问题：主动承认不足能提升可信度，也为后续优化留空间。
面试表达话术：我不会把它包装成完全自治 Agent。第一版目标是可控交付，所以选择受控 Workflow。后续我会补 RAG MVP、标准评测集、动态 Planner 和统一执行器治理。
可能追问：如果问为什么不用 LangGraph，可以说项目技术栈是 Java/Spring，选择 Spring AI Alibaba 更贴合后端工程；但理念上借鉴了 StateGraph/Workflow，后续可以比较引入。"""
    ),
    (
        "【系统设计与可解释性】面试高频：如果让你重构下一版，你会怎么做？",
        """设计目的：展示演进路线。
实现方式：第一，把 Phase3 内部 CompletableFuture 显式接入自定义 Executor，补超时和取消；第二，实现 RAG MVP，把用户资料检索注入 ArticleContext；第三，建立 Benchmark，包括 30-50 个主题、人工评分和 LLM-as-Judge；第四，引入 PlannerAgent 或状态图，让部分流程从固定 Pipeline 演进为动态 Workflow；第五，接 OpenTelemetry，把 AgentLog 升级为标准 trace/span。
解决的问题：这些优化分别解决资源治理、知识增强、质量评测、Agent 自主性和可观测标准化。
面试表达话术：下一版我会保持主流程可控，但在检索决策、工具选择和失败恢复上增加更多动态能力，同时用评测和日志约束模型不确定性。
可能追问：如果问优先级，可以说先补 Benchmark 和 RAG MVP，因为它们对面试展示和产品质量收益最大。"""
    ),
    (
        "【系统设计与可解释性】面试高频：如何回答“这是否只是固定 Pipeline”？",
        """设计目的：把质疑转化为架构选择说明。
实现方式：承认主链路是受控 Workflow/Pipeline：标题 -> 大纲 -> 正文/配图 -> 合成。但指出它不是简单线性 Pipeline，因为存在用户确认、结构化校验、Review 反馈、配图并行分支、工具失败重规划、fallback 和链路日志。
解决的问题：面试官关注的是你是否理解 Agent 自主性边界，而不是是否会包装概念。
面试表达话术：是的，主流程是受控 Workflow，这是我有意选择的，因为内容创作产品需要稳定、可解释和可恢复。Agentic 能力体现在局部决策和反馈闭环，而不是让模型自由决定所有步骤。
可能追问：如果问怎么增强自主性，可以说引入 PlannerAgent 输出执行计划，再由 Orchestrator 校验计划是否合法，形成“模型规划 + 工程约束”的混合架构。"""
    ),
    (
        "【系统设计与可解释性】面试高频：如何回答“RAG 偏轻量怎么办”？",
        """设计目的：避免把未完成模块硬说成核心能力。
实现方式：回答时先说明当前主链路核心是 Multi-Agent Workflow，RAG 是第二阶段增强；再解释 RAG 设计目标是个人知识库、历史文章风格、品牌资料和专业知识增强；最后给出明确落地计划：文档解析、chunk、embedding、PGVector、RetrievalDecisionService、retrievedContext 注入、召回日志。
解决的问题：既不回避不足，也说明你知道如何补齐。
面试表达话术：RAG 当前确实不是项目最强亮点，所以我不会把它作为主卖点。我更强调已落地的结构化输出、工具调度和 Review 闭环；RAG 会作为可解释知识增强模块补齐。
可能追问：如果问 48 小时能补什么，就回答先做 Deterministic RAG MVP，不做复杂 Agentic RAG。"""
    ),
    (
        "【系统设计与可解释性】面试高频：如何回答“缺少标准 Benchmark”？",
        """设计目的：承认内容生成项目的评估短板，并给出可执行方案。
实现方式：当前项目有单元测试覆盖 JsonStructuredOutputService、ImageToolExecutor、ContentMergerAgent 等关键模块，但内容质量缺少固定 Benchmark。后续会构造固定主题集，保存期望风格、人工评分标准和参考样例；每次改 Prompt、模型或工具策略后批量运行，统计结构化失败率、Review 得分、人工评分、耗时和 fallback 率。
解决的问题：Benchmark 能防止 Prompt 调优只看个例，避免一次优化让另一个场景退化。
面试表达话术：我会承认现在更多是工程链路测试，内容质量评测还不够系统。下一步要把主观质量转成可重复的离线评测流程。
可能追问：如果问测试优先级，可以说结构化输出和工具降级必须自动化测试，内容质量先用小规模人工集 + LLM-as-Judge。"""
    ),
]


def col_name(idx):
    name = ""
    while idx:
        idx, rem = divmod(idx - 1, 26)
        name = chr(65 + rem) + name
    return name


def cell_xml(row, col, value, style=1):
    ref = f"{col_name(col)}{row}"
    text = html.escape(str(value), quote=False)
    return f'<c r="{ref}" t="inlineStr" s="{style}"><is><t xml:space="preserve">{text}</t></is></c>'


def write_xlsx(rows, path):
    sheet_rows = []
    for r, row in enumerate(rows, 1):
        height = 30 if r == 1 else 150
        style = 2 if r == 1 else 1
        cells = "".join(cell_xml(r, c, row[c - 1] if c <= len(row) else "", style) for c in range(1, 4))
        sheet_rows.append(f'<row r="{r}" ht="{height}" customHeight="1">{cells}</row>')
    sheet = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
  <cols><col min="1" max="1" width="8" customWidth="1"/><col min="2" max="2" width="52" customWidth="1"/><col min="3" max="3" width="140" customWidth="1"/></cols>
  <sheetData>{''.join(sheet_rows)}</sheetData>
</worksheet>'''
    styles = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="12"/><name val="Arial"/></font></fonts>
  <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFEAF2F8"/><bgColor indexed="64"/></patternFill></fill></fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="1" borderId="0" xfId="0" applyAlignment="1" applyFill="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf></cellXfs>
  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>'''
    files = {
        "[Content_Types].xml": '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>''',
        "_rels/.rels": '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>''',
        "xl/workbook.xml": '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Interview QA" sheetId="1" r:id="rId1"/></sheets></workbook>''',
        "xl/_rels/workbook.xml.rels": '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>''',
        "xl/styles.xml": styles,
        "xl/worksheets/sheet1.xml": sheet,
    }
    def write_zip(target):
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
            for name, content in files.items():
                z.writestr(name, content)

    tmp = path.with_suffix(".tmp.xlsx")
    write_zip(tmp)
    try:
        os.replace(tmp, path)
        return path
    except PermissionError:
        fallback = path.with_name(path.stem + "-system-design-20260519.xlsx")
        write_zip(fallback)
        return fallback


def write_html(rows, path):
    cards = []
    for no, question, answer in rows[1:]:
        section = "系统设计与可解释性" if question.startswith("【系统设计与可解释性】") else "基础项目深挖"
        q = re.sub(r"^【系统设计与可解释性】", "", question)
        answer_html = "<br>".join(html.escape(answer).splitlines())
        cards.append(f'''
        <details class="qa-card">
          <summary><span class="num">{html.escape(no)}</span><span class="tag">{section}</span><span class="question">{html.escape(q)}</span></summary>
          <div class="answer">{answer_html}</div>
        </details>''')
    page = f'''<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Multi-Agent-Creator 项目面试深挖问答</title>
  <style>
    body {{ margin: 0; font-family: Arial, "Microsoft YaHei", sans-serif; background: #f6f8fb; color: #1f2937; }}
    header {{ position: sticky; top: 0; z-index: 10; background: #ffffff; border-bottom: 1px solid #e5e7eb; padding: 18px 28px; }}
    h1 {{ margin: 0 0 8px; font-size: 22px; }}
    .meta {{ color: #64748b; font-size: 14px; }}
    main {{ max-width: 1180px; margin: 0 auto; padding: 24px; }}
    .toolbar {{ display: flex; gap: 12px; margin-bottom: 18px; flex-wrap: wrap; }}
    button {{ border: 1px solid #cbd5e1; background: #fff; color: #334155; border-radius: 6px; padding: 8px 12px; cursor: pointer; }}
    button:hover {{ background: #eef2ff; }}
    .qa-card {{ background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; margin-bottom: 12px; overflow: hidden; }}
    summary {{ cursor: pointer; display: grid; grid-template-columns: 56px 132px 1fr; gap: 12px; align-items: center; padding: 14px 16px; font-weight: 700; }}
    summary::-webkit-details-marker {{ display: none; }}
    .num {{ color: #475569; }}
    .tag {{ color: #0f766e; font-size: 13px; font-weight: 600; }}
    .question {{ line-height: 1.5; }}
    .answer {{ border-top: 1px solid #e5e7eb; padding: 16px 20px; line-height: 1.75; white-space: normal; color: #334155; }}
    @media (max-width: 720px) {{ summary {{ grid-template-columns: 42px 1fr; }} .tag {{ display: none; }} main {{ padding: 14px; }} }}
  </style>
</head>
<body>
  <header>
    <h1>Multi-Agent-Creator 项目面试深挖问答</h1>
    <div class="meta">共 {len(rows) - 1} 题，包含基础项目深挖、系统设计与可解释性、RAG 规划、项目不足与优化方向。</div>
  </header>
  <main>
    <div class="toolbar">
      <button onclick="document.querySelectorAll('details').forEach(d=>d.open=true)">全部展开</button>
      <button onclick="document.querySelectorAll('details').forEach(d=>d.open=false)">全部收起</button>
    </div>
    {''.join(cards)}
  </main>
</body>
</html>'''
    path.write_text(page, encoding="utf-8")


def main():
    rows = clean_existing(read_existing_rows(XLSX_PATH))
    start = len(rows)
    for i, (q, a) in enumerate(SYSTEM_QA, start):
        rows.append([str(i), q, a])
    xlsx_written = write_xlsx(rows, XLSX_PATH)
    write_html(rows, HTML_PATH)
    print(f"rows={len(rows)-1}")
    print(f"xlsx={xlsx_written}")
    print(f"html={HTML_PATH}")


if __name__ == "__main__":
    main()
