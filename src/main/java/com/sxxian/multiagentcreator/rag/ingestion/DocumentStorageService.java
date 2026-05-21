package com.sxxian.multiagentcreator.rag.ingestion;

import com.sxxian.multiagentcreator.config.RagConfig;
import com.sxxian.multiagentcreator.image.adapter.CosService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentStorageService {

    private final CosService cosService;
    private final RagConfig ragConfig;

    public String save(MultipartFile file) throws IOException {
        Path tempFile = Files.createTempFile("rag-upload-", "-" + safeName(file.getOriginalFilename()));
        try {
            file.transferTo(tempFile);
            try {
                String cosUrl = cosService.uploadFile(tempFile.toFile(), "rag-documents");
                if (cosUrl != null && !cosUrl.isBlank()) {
                    return cosUrl;
                }
            } catch (Exception e) {
                log.warn("COS 文档上传失败，降级到本地文件系统, fileName={}, error={}",
                        file.getOriginalFilename(), e.getMessage());
            }
            Path localDir = Path.of(ragConfig.getLocalStorageDir()).toAbsolutePath().normalize();
            Files.createDirectories(localDir);
            String extension = extensionOf(file.getOriginalFilename());
            Path target = localDir.resolve(UUID.randomUUID() + extension);
            Files.copy(tempFile, target);
            return target.toUri().toString();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private String safeName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "document";
        }
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int index = fileName.lastIndexOf('.');
        return index >= 0 ? fileName.substring(index) : "";
    }
}
