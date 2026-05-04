package com.sxxian.multiagentcreator.model.dto.article;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 确认大纲请求
 *
 */
@Data
public class ArticleConfirmOutlineRequest implements Serializable {

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 用户编辑后的大纲
     */
    private List<ArticleState.OutlineSection> outline;

    private static final long serialVersionUID = 1L;
}
