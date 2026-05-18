package com.sxxian.multiagentcreator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "graphviz")
@Data
public class GraphvizConfig {

    /**
     * Graphviz CLI command. Use full path when dot is not on PATH.
     */
    private String cliCommand = "dot";

    /**
     * Output format: svg/png/pdf.
     */
    private String outputFormat = "svg";

    /**
     * Graph background color.
     */
    private String backgroundColor = "transparent";

    /**
     * Command timeout in milliseconds.
     */
    private Long timeout = 30000L;
}
