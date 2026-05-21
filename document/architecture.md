# System Architecture

## Goals

This project is being reorganized by business domain. The first refactor phase keeps external behavior stable while making package boundaries and naming clearer.

Public contracts stay unchanged:

- REST paths remain unchanged, including `/article/**` and `/knowledge-base/**`.
- SSE message types continue to use `SseMessageTypeEnum`.
- Database tables and fields are not changed by this package refactor.
- Baseline and orchestrated article generation paths are both retained.

## Domain Packages

```text
com.sxxian.multiagentcreator
├── article
│   ├── application
│   ├── workflow
│   ├── workflow.legacy
│   ├── agent
│   └── review
├── image
│   ├── planning
│   ├── execution
│   └── adapter
├── rag
│   ├── ingestion
│   ├── retrieval
│   └── persistence
├── billing
├── user
├── eval
├── common/config/aop/annotation/exception/utils
├── controller
├── mapper
├── manager
├── model
└── service
```

`model/dto/entity/enums/vo` remains mostly shared for now. These classes should move by domain later only after call sites stabilize.

## Article Module

`article.application` is the application entry point for article generation tasks.

- `ArticleGenerationApplicationService` owns task lifecycle, phase updates, persistence calls, and workflow selection.
- `ArticleGenerationEventPublisher` owns SSE event translation and emission.
- Controllers should depend on `ArticleGenerationApplicationService`, not on workflow or agent classes.

`article.workflow` owns generation orchestration.

- `OrchestratedArticleWorkflow` is the state-graph/orchestrated path.
- `article.workflow.legacy.LegacyArticleWorkflow` is the baseline path.
- `ContentMergeService` is a deterministic content composition service, not an agent.

`article.agent` and `article.review` contain LLM decision components.

- `ArticleAgent` generates titles, outlines, and article content.
- `ReviewAgent` evaluates generated text, image plans, and image results.

## Image Module

`image.planning` contains image planning and replanning.

- `ImageAgent` decides image requirements and replans failed image requirements from review observations.

`image.execution` contains image requirement execution.

- `ImageToolExecutionService` executes requirements, calls tools, reviews results, retries, and applies fallback.
- `ImageGenerationTool` is the tool-facing facade used by execution and agent tool calls.

`image.adapter` contains concrete providers and infrastructure adapters.

- Pexels, China image search, Qwen image generation, Graphviz, Mermaid, Iconify, emoji packs, SVG diagram generation, Nano Banana, COS upload, and `ImageServiceStrategy` live here.
- Article controllers and article application services should not depend on this package directly.

## RAG Module

`rag.ingestion` owns document indexing.

- Parse uploaded files.
- Chunk content.
- Create embeddings.
- Write indexed chunks.

`rag.retrieval` owns retrieval during article generation.

- `RagService` is the article-generation-facing interface.
- Retrieval decision, vector search orchestration, and context assembly are kept here.

`rag.persistence` hides pgvector access.

- `PgVectorKnowledgeRepository` owns table setup, chunk writes, deletes, and vector search.

Document upload controllers should work with document state and services, not low-level ingestion or pgvector details.

## Legacy And Orchestrated Paths

The two article generation paths are intentionally kept:

- Legacy path: `LegacyArticleWorkflow`
- Orchestrated path: `OrchestratedArticleWorkflow`

`ArticleGenerationApplicationService` selects the path through `AgentConfig`. This keeps the public API stable and allows the orchestrated path to mature without deleting the baseline path.

## Architecture Rules

- Classes named `Agent` should be LLM decision components or graph node actions.
- Deterministic composition and tool execution should use service or workflow names, not agent names.
- Controllers depend on application services.
- Application services can depend on workflows and domain-facing services.
- Workflows can depend on agents and image execution.
- Article application code must not depend on `image.adapter`.
- RAG persistence stays behind `rag.persistence`.
