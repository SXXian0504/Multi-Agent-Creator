package com.sxxian.multiagentcreator.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChinaImageSearchServiceTest {

    @Test
    void extractsOriginalImageUrlFromBingIuscMetadata() {
        ChinaImageSearchService service = new ChinaImageSearchService();

        String html = """
                <div class="dgControl">
                  <a class="iusc" m='{"murl":"https://img.doubanio.com/view/photo/l/public/p1.jpg","purl":"https://movie.douban.com/subject/1/","turl":"https://tse1-mm.cn.bing.net/th?id=1"}'></a>
                </div>
                """;

        List<ChinaImageSearchService.ImageCandidate> candidates = service.extractCandidates(html);

        assertFalse(candidates.isEmpty());
        assertEquals("https://img.doubanio.com/view/photo/l/public/p1.jpg", candidates.get(0).imageUrl());
        assertEquals("https://movie.douban.com/subject/1/", candidates.get(0).pageUrl());
    }
}
