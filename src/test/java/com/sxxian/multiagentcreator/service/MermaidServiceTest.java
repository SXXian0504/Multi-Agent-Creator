package com.sxxian.multiagentcreator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MermaidServiceTest {

    @Test
    void sanitizesNodeLabelWithRawLineBreakAndParentheses() {
        String raw = """
                ```mermaid
                flowchart TD
                  A[Real-time Visual Input
                (Camera/Depth Stream)] --> B[Spatial AI Model]
                ```
                """;

        String sanitized = MermaidService.sanitizeMermaidCode(raw);

        assertFalse(sanitized.contains("```"));
        assertTrue(sanitized.contains("A[\"Real-time Visual Input<br/>(Camera/Depth Stream)\"]"));
        assertTrue(sanitized.contains("B[\"Spatial AI Model\"]"));
    }

    @Test
    void sanitizesEscapedLineBreakAndQuotesInLabel() {
        String raw = "flowchart TB\n  N1[Input \\n \"Camera\"] --> N2{Ready?}";

        String sanitized = MermaidService.sanitizeMermaidCode(raw);

        assertEquals("flowchart TB\n  N1[\"Input <br/> &quot;Camera&quot;\"] --> N2{\"Ready?\"}", sanitized);
    }
}
