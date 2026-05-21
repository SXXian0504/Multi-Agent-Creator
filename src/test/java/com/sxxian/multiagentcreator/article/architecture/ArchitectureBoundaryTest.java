package com.sxxian.multiagentcreator.article.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/sxxian/multiagentcreator");

    @Test
    void oldNonAgentImplementationsDoNotRemainUnderAgentPackage() {
        List<Path> oldAgentNamedFiles = List.of(
                SOURCE_ROOT.resolve("agent/agents/ContentMergerAgent.java"),
                SOURCE_ROOT.resolve("agent/ImageToolExecutor.java"),
                SOURCE_ROOT.resolve("agent/ArticleAgentOrchestrator.java")
        );

        for (Path path : oldAgentNamedFiles) {
            assertFalse(Files.exists(path), "Legacy non-agent class should not remain under agent package: " + path);
        }
    }

    @Test
    void articleControllerDependsOnApplicationServiceOnly() throws IOException {
        Path controller = SOURCE_ROOT.resolve("controller/ArticleController.java");
        String source = Files.readString(controller);

        assertTrue(source.contains("article.application.ArticleGenerationApplicationService"));
        assertFalse(source.contains("article.workflow."), "ArticleController must not depend on workflow internals");
        assertFalse(source.contains("article.agent."), "ArticleController must not depend on agent internals");
        assertFalse(source.contains("image.adapter."), "ArticleController must not depend on image adapters");
    }

    @Test
    void articleApplicationDoesNotReachIntoImageAdapters() throws IOException {
        Path applicationDir = SOURCE_ROOT.resolve("article/application");
        try (Stream<Path> files = Files.walk(applicationDir)) {
            List<Path> javaFiles = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                assertFalse(source.contains("image.adapter."),
                        "Article application layer must not depend on image adapter details: " + javaFile);
            }
        }
    }
}
