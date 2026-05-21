package com.sxxian.multiagentcreator.service.impl;

import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.sxxian.multiagentcreator.mapper.KnowledgeIngestionJobMapper;
import com.sxxian.multiagentcreator.model.entity.KnowledgeIngestionJob;
import com.sxxian.multiagentcreator.service.KnowledgeIngestionJobService;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIngestionJobServiceImpl extends ServiceImpl<KnowledgeIngestionJobMapper, KnowledgeIngestionJob>
        implements KnowledgeIngestionJobService {
}
