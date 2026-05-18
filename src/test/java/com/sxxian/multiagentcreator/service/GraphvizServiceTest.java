package com.sxxian.multiagentcreator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphvizServiceTest {

    @Test
    void stripsMarkdownFenceFromDotCode() {
        String raw = """
                ```dot
                digraph G {
                  A -> B;
                }
                ```
                """;

        String sanitized = GraphvizService.sanitizeDotCode(raw);

        assertTrue(sanitized.startsWith("digraph G {"));
        assertTrue(sanitized.contains("multi-agent-creator graphviz defaults"));
        assertTrue(sanitized.contains("rankdir=LR"));
        assertTrue(sanitized.contains("fontsize=18"));
        assertTrue(sanitized.contains("A -> B;"));
    }

    @Test
    void rejectsNonDotContent() {
        String sanitized = GraphvizService.sanitizeDotCode("flowchart TD\nA --> B");

        assertTrue(sanitized.isBlank());
    }

    @Test
    void removesLayoutOverridesThatCreateUnreadableCanvas() {
        String raw = """
                digraph G {
                  graph [rankdir=TB, size="30,20", ratio=fill, nodesep=5, ranksep=6];
                  A [pos="0,0!"];
                  B [pin=true];
                  A -> B;
                }
                """;

        String sanitized = GraphvizService.sanitizeDotCode(raw);

        assertTrue(sanitized.contains("rankdir=LR"));
        assertTrue(sanitized.contains("nodesep=\"0.45\""));
        assertTrue(sanitized.contains("ranksep=\"0.65\""));
        assertTrue(sanitized.contains("A -> B;"));
        assertTrue(!sanitized.contains("size=\"30,20\""));
        assertTrue(!sanitized.contains("pos=\"0,0!\""));
        assertTrue(!sanitized.contains("pin=true"));
        assertTrue(!sanitized.contains("[]"));
    }

    @Test
    void normalizesEmptyAttributeListsAfterOverrideRemoval() {
        String raw = """
                digraph G {
                  graph [
                    rankdir=TB,
                    size="30,20",
                    ratio=fill
                  ];
                  A [pos="0,0!"];
                  B [label="保留标签", pin=true];
                  A -> B;
                }
                """;

        String sanitized = GraphvizService.sanitizeDotCode(raw);

        assertTrue(!sanitized.contains("rankdir=TB"));
        assertTrue(!sanitized.contains("ratio=fill"));
        assertTrue(!sanitized.contains("A []"));
        assertTrue(!sanitized.contains("pin=true"));
        assertTrue(sanitized.contains("A ;") || sanitized.contains("A;"));
        assertTrue(sanitized.contains("B [label=\"保留标签\""));
        assertTrue(sanitized.contains("A -> B;"));
    }
}
