package com.sxxian.multiagentcreator.model.vo;

import com.sxxian.multiagentcreator.model.entity.KnowledgeBase;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class KnowledgeBaseVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String description;
    private String type;
    private String status;
    private LocalDateTime createTime;

    public static KnowledgeBaseVO fromEntity(KnowledgeBase entity) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setType(entity.getType());
        vo.setStatus(entity.getStatus());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
