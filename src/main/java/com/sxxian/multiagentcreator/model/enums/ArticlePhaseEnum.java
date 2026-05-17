package com.sxxian.multiagentcreator.model.enums;

import lombok.Getter;

@Getter
public enum ArticlePhaseEnum {

    PENDING("PENDING", "等待处理"),

    TITLE_GENERATING("TITLE_GENERATING", "生成标题中"),
    TITLE_REVIEWING("TITLE_REVIEWING", "标题评审中"),
    TITLE_WAITING_USER_CONFIRM("TITLE_WAITING_USER_CONFIRM", "等待用户选择或反馈标题"),
    /**
     * 兼容旧版前端/接口命名，后续可统一迁移到 TITLE_WAITING_USER_CONFIRM。
     */
    TITLE_SELECTING("TITLE_SELECTING", "等待选择标题"),

    OUTLINE_GENERATING("OUTLINE_GENERATING", "生成大纲中"),
    OUTLINE_REVIEWING("OUTLINE_REVIEWING", "大纲评审中"),
    OUTLINE_WAITING_USER_CONFIRM("OUTLINE_WAITING_USER_CONFIRM", "等待用户确认或反馈大纲"),
    /**
     * 兼容旧版前端/接口命名，后续可统一迁移到 OUTLINE_WAITING_USER_CONFIRM。
     */
    OUTLINE_EDITING("OUTLINE_EDITING", "等待编辑大纲"),

    CONTENT_GENERATING("CONTENT_GENERATING", "生成正文中"),
    CONTENT_REVIEWING("CONTENT_REVIEWING", "正文评审中"),
    CONTENT_WAITING_USER_CONFIRM("CONTENT_WAITING_USER_CONFIRM", "等待用户确认或反馈正文"),

    IMAGE_PLANNING("IMAGE_PLANNING", "配图计划生成中"),
    IMAGE_EXECUTING("IMAGE_EXECUTING", "图片工具执行中"),
    IMAGE_REVIEWING("IMAGE_REVIEWING", "图片结果评审中"),
    IMAGE_REPLANNING("IMAGE_REPLANNING", "图片结果未通过，重新规划中"),

    MERGING("MERGING", "图文合成中"),
    COMPLETED("COMPLETED", "全流程完成"),
    FAILED("FAILED", "任务失败");

    /**
     * 阶段值
     */
    private final String value;

    /**
     * 阶段描述
     */
    private final String description;

    ArticlePhaseEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 根据值获取枚举
     *
     * @param value 阶段值
     * @return 枚举实例
     */
    public static ArticlePhaseEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (ArticlePhaseEnum phaseEnum : values()) {
            if (phaseEnum.getValue().equals(value)) {
                return phaseEnum;
            }
        }
        return null;
    }

    /**
     * 校验是否可以转换到目标阶段
     *
     * @param targetPhase 目标阶段
     * @return 是否可以转换
     */
    public boolean canTransitionTo(ArticlePhaseEnum targetPhase) {
        if (targetPhase == null) {
            return false;
        }

        // 定义合法的状态转换
        return switch (this) {
            case PENDING -> targetPhase == TITLE_GENERATING || targetPhase == FAILED;
            case TITLE_GENERATING -> targetPhase == TITLE_REVIEWING
                    || targetPhase == TITLE_WAITING_USER_CONFIRM
                    || targetPhase == TITLE_SELECTING
                    || targetPhase == FAILED;
            case TITLE_REVIEWING -> targetPhase == TITLE_WAITING_USER_CONFIRM
                    || targetPhase == TITLE_SELECTING
                    || targetPhase == TITLE_GENERATING
                    || targetPhase == FAILED;
            case TITLE_WAITING_USER_CONFIRM, TITLE_SELECTING -> targetPhase == OUTLINE_GENERATING
                    || targetPhase == TITLE_GENERATING
                    || targetPhase == FAILED;
            case OUTLINE_GENERATING -> targetPhase == OUTLINE_REVIEWING
                    || targetPhase == OUTLINE_WAITING_USER_CONFIRM
                    || targetPhase == OUTLINE_EDITING
                    || targetPhase == FAILED;
            case OUTLINE_REVIEWING -> targetPhase == OUTLINE_WAITING_USER_CONFIRM
                    || targetPhase == OUTLINE_EDITING
                    || targetPhase == OUTLINE_GENERATING
                    || targetPhase == FAILED;
            case OUTLINE_WAITING_USER_CONFIRM, OUTLINE_EDITING -> targetPhase == CONTENT_GENERATING
                    || targetPhase == OUTLINE_GENERATING
                    || targetPhase == FAILED;
            case CONTENT_GENERATING -> targetPhase == CONTENT_REVIEWING
                    || targetPhase == CONTENT_WAITING_USER_CONFIRM
                    || targetPhase == IMAGE_PLANNING
                    || targetPhase == IMAGE_EXECUTING
                    || targetPhase == MERGING
                    || targetPhase == COMPLETED
                    || targetPhase == FAILED;
            case CONTENT_REVIEWING -> targetPhase == CONTENT_WAITING_USER_CONFIRM
                    || targetPhase == CONTENT_GENERATING
                    || targetPhase == IMAGE_PLANNING
                    || targetPhase == FAILED;
            case CONTENT_WAITING_USER_CONFIRM -> targetPhase == CONTENT_GENERATING
                    || targetPhase == IMAGE_PLANNING
                    || targetPhase == FAILED;
            case IMAGE_PLANNING -> targetPhase == IMAGE_EXECUTING
                    || targetPhase == IMAGE_REVIEWING
                    || targetPhase == IMAGE_REPLANNING
                    || targetPhase == MERGING
                    || targetPhase == FAILED;
            case IMAGE_EXECUTING -> targetPhase == IMAGE_REVIEWING
                    || targetPhase == IMAGE_REPLANNING
                    || targetPhase == MERGING
                    || targetPhase == FAILED;
            case IMAGE_REVIEWING -> targetPhase == IMAGE_REPLANNING
                    || targetPhase == MERGING
                    || targetPhase == FAILED;
            case IMAGE_REPLANNING -> targetPhase == IMAGE_PLANNING
                    || targetPhase == IMAGE_EXECUTING
                    || targetPhase == FAILED;
            case MERGING -> targetPhase == COMPLETED || targetPhase == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }
}
