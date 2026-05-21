package com.sxxian.multiagentcreator.rag.retrieval;

import com.sxxian.multiagentcreator.config.RagConfig;
import com.sxxian.multiagentcreator.model.dto.rag.KnowledgeChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RagContextBuilder {

    private final RagConfig ragConfig;

    public String build(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\n\n【用户知识库参考资料】\n");
        builder.append("以下资料来自用户授权的个人知识库。写作时优先遵循事实、设定和表达风格；不要编造资料中没有的具体数据。\n");
        int index = 1;
        for (KnowledgeChunk chunk : chunks) {
            String item = "\n[资料" + index++ + "]\n" + chunk.getContent().trim() + "\n";
            if (builder.length() + item.length() > ragConfig.getContextMaxChars()) {
                break;
            }
            builder.append(item);
        }
        return builder.toString();
    }
}
