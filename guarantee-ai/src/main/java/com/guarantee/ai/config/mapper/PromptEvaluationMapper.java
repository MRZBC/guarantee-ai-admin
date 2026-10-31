package com.guarantee.ai.config.mapper;

import com.guarantee.ai.config.PromptEvaluation;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PromptEvaluationMapper {
    @Select("SELECT COUNT(*) FROM ai_prompt_evaluation WHERE status='RUNNING'")
    int running();
    @Insert("""
        INSERT INTO ai_prompt_evaluation
          (id,version_no,content_hash,content,baseline_content,context_hash,context_snapshot,suite_version,status,summary,created_by)
        VALUES (#{id},#{versionNo},#{contentHash},#{content},#{baselineContent},#{contextHash},#{contextSnapshot},
          #{suiteVersion},#{status},#{summary},#{createdBy})
        """)
    int insert(PromptEvaluation task);

    @Select("SELECT * FROM ai_prompt_evaluation WHERE id=#{id}")
    PromptEvaluation select(String id);

    @Select("SELECT * FROM ai_prompt_evaluation WHERE version_no=#{versionNo} ORDER BY created_at DESC,id DESC LIMIT 1")
    PromptEvaluation latest(int versionNo);

    @Update("""
        UPDATE ai_prompt_evaluation SET status=#{status},summary=#{summary},results=#{results},finished_at=NOW(6)
        WHERE id=#{id} AND status='RUNNING'
        """)
    int finish(PromptEvaluation task);

    @Update("""
        UPDATE ai_prompt_evaluation SET status='ERROR',summary='评测超时或进程中断，请重新评测',finished_at=NOW(6)
        WHERE status='RUNNING' AND created_at < DATE_SUB(NOW(6),INTERVAL 20 MINUTE)
        """)
    int expire();
}
