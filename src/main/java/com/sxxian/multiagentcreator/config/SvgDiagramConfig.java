package com.sxxian.multiagentcreator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static com.sxxian.multiagentcreator.constant.ArticleConstant.SVG_DEFAULT_HEIGHT;
import static com.sxxian.multiagentcreator.constant.ArticleConstant.SVG_DEFAULT_WIDTH;

@Configuration
@ConfigurationProperties(prefix = "svg-diagram")
@Data
public class SvgDiagramConfig {

    /**
     * 默认宽度
     */
    private Integer defaultWidth = SVG_DEFAULT_WIDTH;

    /**
     * 默认高度
     */
    private Integer defaultHeight = SVG_DEFAULT_HEIGHT;

    /**
     * COS 存储文件夹
     */
    private String folder = "svg-diagrams";
}
