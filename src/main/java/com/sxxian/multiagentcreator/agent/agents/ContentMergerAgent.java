package com.sxxian.multiagentcreator.agent.agents;


import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sxxian.multiagentcreator.agent.tools.ImageGenerationTool;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 鍥炬枃鍚堟垚 Agent
 * 灏嗛厤鍥炬彃鍏ュ埌姝ｆ枃鐨勭浉搴斾綅缃?
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentMergerAgent implements NodeAction {

    public static final String INPUT_CONTENT = "content";
    public static final String INPUT_IMAGES = "images";
    public static final String INPUT_IMAGE_EXECUTION_TRACES = "imageExecutionTraces";
    public static final String OUTPUT_FULL_CONTENT = "fullContent";
    public static final String OUTPUT_IMAGE_EXECUTION_TRACES = "imageExecutionTraces";

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String content = state.value(INPUT_CONTENT)
                .map(Object::toString)
                .orElseThrow(() -> new IllegalArgumentException("缂哄皯姝ｆ枃鍐呭鍙傛暟"));

        @SuppressWarnings("unchecked")
        List<ArticleState.ImageResult> images = state.value(INPUT_IMAGES)
                .map(v -> {
                    if (v instanceof List) {
                        List<?> list = (List<?>) v;
                        if (list.isEmpty()) {
                            return new ArrayList<ArticleState.ImageResult>();
                        }
                        // 妫€鏌ュ垪琛ㄥ厓绱犵被鍨?
                        if (list.get(0) instanceof ArticleState.ImageResult) {
                            return (List<ArticleState.ImageResult>) v;
                        }
                        // 灏濊瘯杞崲
                        return convertToImageResults(list);
                    }
                    return new ArrayList<ArticleState.ImageResult>();
                })
                .orElse(new ArrayList<>());

        List<ArticleState.ImageExecutionTrace> traces = readTraces(state);

        log.info("ContentMergerAgent 寮€濮嬫墽琛? 姝ｆ枃闀垮害={}, 鍥剧墖鏁伴噺={}", content.length(), images.size());

        String fullContent = mergeImagesIntoContent(content, images, traces);

        log.info("ContentMergerAgent 鎵ц瀹屾垚: 瀹屾暣鍐呭闀垮害={}", fullContent.length());

        return Map.of(OUTPUT_FULL_CONTENT, fullContent, OUTPUT_IMAGE_EXECUTION_TRACES, traces);
    }
    public String mergeImagesIntoContent(String content, List<ArticleState.ImageResult> images) {
        return mergeImagesIntoContent(content, images, new ArrayList<>());
    }

    public String insertPlaceholdersIntoContent(String content, List<ArticleState.ImageRequirement> requirements) {
        if (content == null || content.isBlank() || requirements == null || requirements.isEmpty()) {
            return content;
        }

        String contentWithPlaceholders = content;
        for (ArticleState.ImageRequirement requirement : requirements) {
            if (requirement.getPosition() != null && requirement.getPosition() == 1) {
                continue;
            }
            String placeholder = requirement.getPlaceholderId();
            if (placeholder == null || placeholder.isBlank() || contentWithPlaceholders.contains(placeholder)) {
                continue;
            }
            String inserted = insertPlaceholderAfterSection(contentWithPlaceholders, requirement);
            contentWithPlaceholders = inserted != null
                    ? inserted
                    : appendPlaceholder(contentWithPlaceholders, requirement);
        }
        return contentWithPlaceholders;
    }

    private String mergeImagesIntoContent(String content, List<ArticleState.ImageResult> images,
                                          List<ArticleState.ImageExecutionTrace> traces) {
        if (images == null || images.isEmpty()) {
            return content;
        }

        String fullContent = content;

        for (ArticleState.ImageResult image : images) {
            if (image.getPosition() != null && image.getPosition() == 1) {
                fullContent = prependCoverImage(fullContent, image);
                addMergeTrace(traces, image, "COVER_PREPENDED");
                continue;
            }

            String placeholder = image.getPlaceholderId();
            log.info("处理图片: position={}, placeholderId={}, url={}",
                    image.getPosition(), placeholder, image.getUrl());

            if (placeholder != null && !placeholder.isEmpty() && fullContent.contains(placeholder)) {
                String imageMarkdown = "![" + safeDescription(image) + "](" + image.getUrl() + ")";
                fullContent = fullContent.replace(placeholder, imageMarkdown);
                addMergeTrace(traces, image, "PLACEHOLDER_REPLACED");
                log.info("成功替换占位符 {}", placeholder);
                continue;
            }

            String inserted = insertAfterSection(fullContent, image);
            if (inserted != null) {
                fullContent = inserted;
                addMergeTrace(traces, image, "SECTION_INSERTED");
            } else {
                fullContent = appendImage(fullContent, image);
                addMergeTrace(traces, image, "APPENDED_AFTER_SECTION_MISS");
                log.warn("正文中未找到图片占位符或匹配章节, position={}, placeholderId={}, sectionTitle={}",
                        image.getPosition(), placeholder, image.getSectionTitle());
            }
        }

        return fullContent;
    }

    private String insertAfterSection(String content, ArticleState.ImageResult image) {
        if (image.getSectionTitle() == null || image.getSectionTitle().isBlank()) {
            return null;
        }
        String[] lines = content.split("\\R", -1);
        String imageMarkdown = "\n\n![" + safeDescription(image) + "](" + image.getUrl() + ")\n";
        StringBuilder sb = new StringBuilder();
        boolean inserted = false;
        for (String line : lines) {
            sb.append(line).append("\n");
            if (!inserted && isMatchingHeading(line, image.getSectionTitle())) {
                sb.append(imageMarkdown);
                inserted = true;
            }
        }
        return inserted ? sb.toString().stripTrailing() : null;
    }

    private String insertPlaceholderAfterSection(String content, ArticleState.ImageRequirement requirement) {
        if (requirement.getSectionTitle() == null || requirement.getSectionTitle().isBlank()) {
            return null;
        }
        String[] lines = content.split("\\R", -1);
        String placeholderBlock = "\n\n" + requirement.getPlaceholderId() + "\n";
        StringBuilder sb = new StringBuilder();
        boolean inserted = false;
        for (String line : lines) {
            sb.append(line).append("\n");
            if (!inserted && isMatchingHeading(line, requirement.getSectionTitle())) {
                sb.append(placeholderBlock);
                inserted = true;
            }
        }
        return inserted ? sb.toString().stripTrailing() : null;
    }

    private boolean isMatchingHeading(String line, String sectionTitle) {
        String normalizedLine = line.trim().replaceFirst("^#{1,6}\\s*", "").trim();
        String normalizedTitle = sectionTitle.trim();
        return normalizedLine.equals(normalizedTitle)
                || normalizedLine.contains(normalizedTitle)
                || (!normalizedLine.isBlank() && normalizedTitle.contains(normalizedLine));
    }

    private String appendImage(String content, ArticleState.ImageResult image) {
        return content + "\n\n![" + safeDescription(image) + "](" + image.getUrl() + ")";
    }

    private String prependCoverImage(String content, ArticleState.ImageResult image) {
        if (image.getUrl() == null || image.getUrl().isBlank() || content.contains(image.getUrl())) {
            return content;
        }
        return "![" + safeDescription(image) + "](" + image.getUrl() + ")\n\n" + content;
    }

    private String appendPlaceholder(String content, ArticleState.ImageRequirement requirement) {
        if (requirement.getSectionTitle() != null && !requirement.getSectionTitle().isBlank()) {
            return content + "\n\n## " + requirement.getSectionTitle() + "\n\n" + requirement.getPlaceholderId();
        }
        return content + "\n\n" + requirement.getPlaceholderId();
    }

    private String safeDescription(ArticleState.ImageResult image) {
        return image.getDescription() != null ? image.getDescription() : "配图";
    }

    private void addMergeTrace(List<ArticleState.ImageExecutionTrace> traces,
                               ArticleState.ImageResult image,
                               String mergeAction) {
        ArticleState.ImageExecutionTrace trace = new ArticleState.ImageExecutionTrace();
        trace.setPosition(image.getPosition());
        trace.setPlaceholderId(image.getPlaceholderId());
        trace.setUrl(image.getUrl());
        trace.setMethod(image.getMethod());
        trace.setMergeAction(mergeAction);
        trace.setFinalStatus("MERGED");
        traces.add(trace);
    }

    /**
     * 杞崲鍒楄〃涓?ImageResult 鍒楄〃
     */
    private List<ArticleState.ImageResult> convertToImageResults(List<?> list) {
        List<ArticleState.ImageResult> results = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof ArticleState.ImageResult) {
                results.add((ArticleState.ImageResult) item);
            } else if (item instanceof ImageGenerationTool.ImageGenerationResult) {
                // 浠?ImageGenerationTool.ImageGenerationResult 杞崲
                ImageGenerationTool.ImageGenerationResult genResult =
                        (ImageGenerationTool.ImageGenerationResult) item;
                if (genResult.isSuccess()) {
                    ArticleState.ImageResult imageResult = new ArticleState.ImageResult();
                    imageResult.setPosition(genResult.getPosition());
                    imageResult.setUrl(genResult.getUrl());
                    imageResult.setMethod(genResult.getMethod());
                    imageResult.setKeywords(genResult.getKeywords());
                    imageResult.setSectionTitle(genResult.getSectionTitle());
                    imageResult.setDescription(genResult.getDescription());
                    imageResult.setPlaceholderId(genResult.getPlaceholderId());
                    results.add(imageResult);
                }
            } else if (item instanceof Map) {
                // 浠?Map 杞崲
                String json = GsonUtils.toJson(item);
                ArticleState.ImageResult imageResult = GsonUtils.fromJson(json, ArticleState.ImageResult.class);
                if (imageResult.getUrl() != null) {
                    results.add(imageResult);
                }
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private List<ArticleState.ImageExecutionTrace> readTraces(OverAllState state) {
        return state.value(INPUT_IMAGE_EXECUTION_TRACES)
                .map(v -> {
                    if (!(v instanceof List<?> list)) {
                        return new ArrayList<ArticleState.ImageExecutionTrace>();
                    }
                    if (list.isEmpty()) {
                        return new ArrayList<ArticleState.ImageExecutionTrace>();
                    }
                    if (list.get(0) instanceof ArticleState.ImageExecutionTrace) {
                        return new ArrayList<>((List<ArticleState.ImageExecutionTrace>) v);
                    }
                    List<ArticleState.ImageExecutionTrace> converted = new ArrayList<>();
                    for (Object item : list) {
                        converted.add(GsonUtils.fromJson(GsonUtils.toJson(item), ArticleState.ImageExecutionTrace.class));
                    }
                    return converted;
                })
                .orElse(new ArrayList<>());
    }
}
