package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.RagEvalCaseEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RAG 评估黄金集 Mapper。
 */
@Mapper
public interface RagEvalCaseMapper extends BaseMapper<RagEvalCaseEntity> {

    /** 取某集合已存在的 chunk 哈希，用于落库前跳过重复用例（HNSW 底层顺序不稳，抽样可能重复） */
    @Select("SELECT chunk_hash FROM rag_eval_case WHERE collection = #{collection}")
    List<String> findHashesByCollection(@Param("collection") String collection);

    /** 按集合取用例（按 id 升序，保证同一集合每次评估顺序稳定） */
    @Select("SELECT * FROM rag_eval_case WHERE collection = #{collection} ORDER BY id LIMIT #{limit}")
    List<RagEvalCaseEntity> findByCollection(@Param("collection") String collection,
                                             @Param("limit") int limit);

    /** 按集合统计用例数 */
    @Select("SELECT COUNT(*) FROM rag_eval_case WHERE collection = #{collection}")
    int countByCollection(@Param("collection") String collection);

    /** 清空某集合的用例（仅 rebuild=true 时调用，会破坏历史轮次可比性） */
    @Delete("DELETE FROM rag_eval_case WHERE collection = #{collection}")
    int deleteByCollection(@Param("collection") String collection);
}
