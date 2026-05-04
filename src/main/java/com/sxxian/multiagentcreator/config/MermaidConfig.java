package com.sxxian.multiagentcreator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "mermaid")
@Data
public class MermaidConfig {

    /**
     * CLI 命令（Windows 下为 mmdc.cmd，Linux/Mac 下为 mmdc）
     */
    private String cliCommand = "mmdc";

    /**
     * 背景颜色（transparent 为透明背景）
     */
    private String backgroundColor = "transparent";

    /**
     * 输出格式（svg/png/pdf）
     */
    private String outputFormat = "svg";

    /**
     * 图片宽度（像素）
     */
    private Integer width = 800;

    /**
     * 命令执行超时时间（毫秒）
     */
    private Long timeout = 30000L;
}
