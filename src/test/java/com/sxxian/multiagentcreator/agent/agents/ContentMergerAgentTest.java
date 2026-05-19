package com.sxxian.multiagentcreator.agent.agents;

import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentMergerAgentTest {

    private final ContentMergerAgent contentMergerAgent = new ContentMergerAgent();

    @Test
    void insertPlaceholdersIntoContentInsertsAfterMatchingHeadingAndSkipsCover() {
        String content = "# Title\n\n## Section A\n\nParagraph A.\n\n## Section B\n\nParagraph B.";

        ArticleState.ImageRequirement cover = new ArticleState.ImageRequirement();
        cover.setPosition(1);
        cover.setPlaceholderId("");

        ArticleState.ImageRequirement sectionImage = new ArticleState.ImageRequirement();
        sectionImage.setPosition(2);
        sectionImage.setSectionTitle("Section B");
        sectionImage.setPlaceholderId("{{IMAGE_PLACEHOLDER_1}}");

        String result = contentMergerAgent.insertPlaceholdersIntoContent(
                content, List.of(cover, sectionImage));

        assertTrue(result.indexOf("## Section B") < result.indexOf("{{IMAGE_PLACEHOLDER_1}}"));
        assertTrue(result.indexOf("{{IMAGE_PLACEHOLDER_1}}") < result.indexOf("Paragraph B."));
        assertEquals(result.indexOf("{{IMAGE_PLACEHOLDER_1}}"), result.lastIndexOf("{{IMAGE_PLACEHOLDER_1}}"));
    }

    @Test
    void insertPlaceholdersIntoContentDoesNotDuplicateExistingPlaceholder() {
        String content = "## Section A\n\n{{IMAGE_PLACEHOLDER_1}}\n\nParagraph A.";
        ArticleState.ImageRequirement sectionImage = new ArticleState.ImageRequirement();
        sectionImage.setPosition(2);
        sectionImage.setSectionTitle("Section A");
        sectionImage.setPlaceholderId("{{IMAGE_PLACEHOLDER_1}}");

        String result = contentMergerAgent.insertPlaceholdersIntoContent(
                content, List.of(sectionImage));

        assertEquals(content, result);
    }

    @Test
    void mergeImagesIntoContentPrependsCoverImage() {
        String content = "## Section A\n\nParagraph A.";

        ArticleState.ImageResult cover = new ArticleState.ImageResult();
        cover.setPosition(1);
        cover.setUrl("https://example.com/cover.png");
        cover.setDescription("cover");

        String result = contentMergerAgent.mergeImagesIntoContent(content, List.of(cover));

        assertTrue(result.startsWith("![cover](https://example.com/cover.png)\n\n## Section A"));
    }

    @Test
    void mergeImagesIntoContentReplacesCoverPlaceholderWhenPresent() {
        String content = "## Section A\n\n{{IMAGE_PLACEHOLDER_1}}\n\nParagraph A.";

        ArticleState.ImageResult cover = new ArticleState.ImageResult();
        cover.setPosition(1);
        cover.setUrl("https://example.com/cover.png");
        cover.setDescription("cover");
        cover.setPlaceholderId("{{IMAGE_PLACEHOLDER_1}}");

        String result = contentMergerAgent.mergeImagesIntoContent(content, List.of(cover));

        assertEquals("## Section A\n\n![cover](https://example.com/cover.png)\n\nParagraph A.", result);
    }
}
