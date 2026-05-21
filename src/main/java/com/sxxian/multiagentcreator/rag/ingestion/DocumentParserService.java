package com.sxxian.multiagentcreator.rag.ingestion;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DocumentParserService {

    private final OkHttpClient httpClient = new OkHttpClient();

    public String parse(String storageUrl, String fileType) throws IOException {
        byte[] bytes = readBytes(storageUrl);
        return switch (fileType.toLowerCase()) {
            case "txt", "md" -> new String(bytes, StandardCharsets.UTF_8);
            case "pdf" -> parsePdf(bytes);
            case "docx" -> parseDocx(bytes);
            default -> throw new IllegalArgumentException("不支持的文档类型: " + fileType);
        };
    }

    private byte[] readBytes(String storageUrl) throws IOException {
        if (storageUrl.startsWith("file:")) {
            return Files.readAllBytes(Path.of(URI.create(storageUrl)));
        }
        Request request = new Request.Builder().url(storageUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("下载文档失败: " + response.code());
            }
            return response.body().bytes();
        }
    }

    private String parsePdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String parseDocx(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return document.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining("\n\n"));
        }
    }
}
