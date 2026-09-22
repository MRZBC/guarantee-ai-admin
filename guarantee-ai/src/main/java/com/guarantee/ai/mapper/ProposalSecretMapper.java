package com.guarantee.ai.mapper;

import com.guarantee.ai.entity.AiOperationSecret;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 提案敏感参数暂存 Mapper。
 */
@Mapper
public interface ProposalSecretMapper {

    /** 同一提案只允许一条密文（后写覆盖）。 */
    int upsert(@Param("proposalId") Long proposalId,
               @Param("cipherText") String cipherText,
               @Param("keyVersion") Integer keyVersion,
               @Param("expiresAt") LocalDateTime expiresAt);

    AiOperationSecret selectByProposalId(@Param("proposalId") Long proposalId);

    int deleteByProposalId(@Param("proposalId") Long proposalId);

    int deleteExpired(@Param("now") LocalDateTime now);
}
