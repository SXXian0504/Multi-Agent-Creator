package com.sxxian.multiagentcreator.model.dto.article;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户对某个生成阶段的反馈。
 */
@Data
public class UserFeedback implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 反馈所属阶段，见 ArticlePhaseEnum。
     */
    private String phase;

    /**
     * 用户反馈内容。
     */
    private String content;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;
}
