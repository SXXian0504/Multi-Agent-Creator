package com.sxxian.multiagentcreator.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.sxxian.multiagentcreator.exception.ErrorCode;
import com.sxxian.multiagentcreator.exception.ThrowUtils;
import com.sxxian.multiagentcreator.mapper.KnowledgeBaseMapper;
import com.sxxian.multiagentcreator.model.dto.rag.KnowledgeBaseCreateRequest;
import com.sxxian.multiagentcreator.model.entity.KnowledgeBase;
import com.sxxian.multiagentcreator.model.entity.KnowledgeDocument;
import com.sxxian.multiagentcreator.model.entity.User;
import com.sxxian.multiagentcreator.model.enums.KnowledgeBaseTypeEnum;
import com.sxxian.multiagentcreator.model.enums.KnowledgeStatusEnum;
import com.sxxian.multiagentcreator.rag.ingestion.DocumentIngestionService;
import com.sxxian.multiagentcreator.rag.ingestion.DocumentStorageService;
import com.sxxian.multiagentcreator.service.KnowledgeBaseService;
import com.sxxian.multiagentcreator.service.KnowledgeDocumentService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements KnowledgeBaseService {

    @Resource
    private KnowledgeDocumentService knowledgeDocumentService;

    @Resource
    private DocumentStorageService documentStorageService;

    @Resource
    private DocumentIngestionService documentIngestionService;

    @Override
    public KnowledgeBase createKnowledgeBase(KnowledgeBaseCreateRequest request, User loginUser) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getName() == null || request.getName().isBlank(),
                ErrorCode.PARAMS_ERROR, "知识库名称不能为空");
        ThrowUtils.throwIf(!KnowledgeBaseTypeEnum.isValid(request.getType()),
                ErrorCode.PARAMS_ERROR, "无效的知识库类型");

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setUserId(loginUser.getId());
        knowledgeBase.setName(request.getName().trim());
        knowledgeBase.setDescription(request.getDescription());
        knowledgeBase.setType(request.getType());
        knowledgeBase.setStatus(KnowledgeStatusEnum.ACTIVE.getValue());
        knowledgeBase.setCreateTime(LocalDateTime.now());
        this.save(knowledgeBase);
        return knowledgeBase;
    }

    @Override
    public List<KnowledgeBase> listMyKnowledgeBases(User loginUser) {
        return this.list(QueryWrapper.create()
                .eq("userId", loginUser.getId())
                .eq("isDelete", 0)
                .orderBy("createTime", false));
    }

    @Override
    public List<KnowledgeBase> listAvailableKnowledgeBases(Long userId, List<Long> selectedIds) {
        QueryWrapper query = QueryWrapper.create()
                .eq("userId", userId)
                .eq("status", KnowledgeStatusEnum.ACTIVE.getValue())
                .eq("isDelete", 0);
        if (selectedIds != null && !selectedIds.isEmpty()) {
            query.in("id", selectedIds);
        }
        return this.list(query);
    }

    @Override
    public KnowledgeDocument uploadDocument(Long knowledgeBaseId, MultipartFile file, User loginUser) {
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "上传文件不能为空");
        KnowledgeBase knowledgeBase = this.getById(knowledgeBaseId);
        ThrowUtils.throwIf(knowledgeBase == null || !knowledgeBase.getUserId().equals(loginUser.getId()),
                ErrorCode.NO_AUTH_ERROR, "无权访问该知识库");

        String fileName = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String fileType = fileType(fileName);
        ThrowUtils.throwIf(!List.of("txt", "md", "pdf", "docx").contains(fileType),
                ErrorCode.PARAMS_ERROR, "仅支持 txt/md/pdf/docx 文档");
        try {
            String storageUrl = documentStorageService.save(file);
            KnowledgeDocument document = new KnowledgeDocument();
            document.setUserId(loginUser.getId());
            document.setKnowledgeBaseId(knowledgeBaseId);
            document.setFileName(fileName);
            document.setFileType(fileType);
            document.setFileSize(file.getSize());
            document.setStorageUrl(storageUrl);
            document.setParseStatus(KnowledgeStatusEnum.PENDING.getValue());
            document.setChunkCount(0);
            document.setCreateTime(LocalDateTime.now());
            knowledgeDocumentService.save(document);
            documentIngestionService.ingestAsync(document, knowledgeBase.getType());
            return document;
        } catch (Exception e) {
            throw new RuntimeException("文档上传失败: " + e.getMessage(), e);
        }
    }

    private String fileType(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index + 1).toLowerCase();
    }
}
