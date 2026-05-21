package com.sxxian.multiagentcreator.controller;

import com.sxxian.multiagentcreator.common.BaseResponse;
import com.sxxian.multiagentcreator.common.ResultUtils;
import com.sxxian.multiagentcreator.exception.ErrorCode;
import com.sxxian.multiagentcreator.exception.ThrowUtils;
import com.sxxian.multiagentcreator.model.dto.rag.KnowledgeBaseCreateRequest;
import com.sxxian.multiagentcreator.model.entity.KnowledgeBase;
import com.sxxian.multiagentcreator.model.entity.KnowledgeDocument;
import com.sxxian.multiagentcreator.model.entity.User;
import com.sxxian.multiagentcreator.model.vo.KnowledgeBaseVO;
import com.sxxian.multiagentcreator.model.vo.KnowledgeDocumentVO;
import com.sxxian.multiagentcreator.service.KnowledgeBaseService;
import com.sxxian.multiagentcreator.service.KnowledgeDocumentService;
import com.sxxian.multiagentcreator.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/knowledge-base")
public class KnowledgeBaseController {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private KnowledgeDocumentService knowledgeDocumentService;

    @Resource
    private UserService userService;

    @PostMapping("/create")
    @Operation(summary = "创建知识库")
    public BaseResponse<KnowledgeBaseVO> create(@RequestBody KnowledgeBaseCreateRequest request,
                                                HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        KnowledgeBase knowledgeBase = knowledgeBaseService.createKnowledgeBase(request, loginUser);
        return ResultUtils.success(KnowledgeBaseVO.fromEntity(knowledgeBase));
    }

    @GetMapping("/list")
    @Operation(summary = "查询我的知识库")
    public BaseResponse<List<KnowledgeBaseVO>> list(HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        List<KnowledgeBaseVO> result = knowledgeBaseService.listMyKnowledgeBases(loginUser)
                .stream()
                .map(KnowledgeBaseVO::fromEntity)
                .toList();
        return ResultUtils.success(result);
    }

    @PostMapping("/{knowledgeBaseId}/upload")
    @Operation(summary = "上传知识库文档")
    public BaseResponse<KnowledgeDocumentVO> upload(@PathVariable Long knowledgeBaseId,
                                                    @RequestPart("file") MultipartFile file,
                                                    HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        KnowledgeDocument document = knowledgeBaseService.uploadDocument(knowledgeBaseId, file, loginUser);
        return ResultUtils.success(KnowledgeDocumentVO.fromEntity(document));
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    @Operation(summary = "查询知识库文档")
    public BaseResponse<List<KnowledgeDocumentVO>> documents(@PathVariable Long knowledgeBaseId,
                                                             HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        KnowledgeBase knowledgeBase = knowledgeBaseService.getById(knowledgeBaseId);
        ThrowUtils.throwIf(knowledgeBase == null || !knowledgeBase.getUserId().equals(loginUser.getId()),
                ErrorCode.NO_AUTH_ERROR, "无权访问该知识库");
        return ResultUtils.success(knowledgeDocumentService.listByKnowledgeBaseId(knowledgeBaseId, loginUser.getId())
                .stream()
                .map(KnowledgeDocumentVO::fromEntity)
                .toList());
    }
}
