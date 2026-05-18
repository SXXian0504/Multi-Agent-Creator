package com.sxxian.multiagentcreator.model.enums;

import lombok.Getter;

/**
 * 结构化输出类型。
 */
@Getter
public enum StructuredOutputTypeEnum {

    TITLE_OPTIONS("title_options", "title_options.schema.json", RootType.ARRAY),
    OUTLINE_RESULT("outline_result", "outline_result.schema.json", RootType.OBJECT),
    IMAGE_PLAN("image_plan", "image_plan.schema.json", RootType.OBJECT),
    OUTLINE_IMAGE_PLAN("outline_image_plan", "outline_image_plan.schema.json", RootType.OBJECT),
    REVIEW_RESULT("review_result", "review_result.schema.json", RootType.OBJECT),
    IMAGE_REVIEW_RESULT("image_review_result", "image_review_result.schema.json", RootType.OBJECT);

    private final String value;
    private final String schemaFileName;
    private final RootType rootType;

    StructuredOutputTypeEnum(String value, String schemaFileName, RootType rootType) {
        this.value = value;
        this.schemaFileName = schemaFileName;
        this.rootType = rootType;
    }

    public enum RootType {
        OBJECT,
        ARRAY
    }
}
