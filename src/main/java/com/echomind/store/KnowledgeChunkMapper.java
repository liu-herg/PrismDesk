package com.echomind.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkEntity> {

    /** 切片 id 由内容算出，同一文档重复导入时覆盖内容而不是插入重复行。 */
    @Insert("""
            INSERT INTO knowledge_chunk (id, doc_title, chunk_index, content, metadata, created_at, updated_at)
            VALUES (#{id}, #{docTitle}, #{chunkIndex}, #{content}, #{metadata}, #{createdAt}, #{updatedAt}) AS new
            ON DUPLICATE KEY UPDATE
                content    = new.content,
                metadata   = new.metadata,
                updated_at = new.updated_at
            """)
    int upsert(KnowledgeChunkEntity chunk);
}
