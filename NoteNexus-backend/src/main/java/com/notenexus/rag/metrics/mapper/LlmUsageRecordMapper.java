package com.notenexus.rag.metrics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notenexus.rag.metrics.model.entity.LlmUsageRecordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 用量记录 Mapper。
 * <p>仅保留写入能力（BaseMapper 提供的 insert 等），用于问答与助手调用后的 Token 计量落库。</p>
 */
@Mapper
public interface LlmUsageRecordMapper extends BaseMapper<LlmUsageRecordEntity> {
}
