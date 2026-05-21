package com.sxxian.multiagentcreator.model.dto.rag;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class KnowledgeBaseCreateRequest implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private String description;
    private String type;
}
