package com.guarantee.ai.service;

import com.guarantee.ai.mapper.ProposalSecretMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 提案敏感参数的一次性加密暂存（D-4 与"提案可执行"之间的取舍）。
 *
 * <p><b>为什么需要它</b>：两件事同时成立才不矛盾——</p>
 * <ul>
 *   <li>D-4 / SYS-A-09：{@code ai_operation_proposal.request_payload} 与
 *       {@code ai_operation_audit} 中不得出现敏感字段明文（审计表可能被导出、直查、备份流转）；</li>
 *   <li>功能要求：用户说"把 user0123 的手机号改成 X"，确认后必须真的改成 X。</li>
 * </ul>
 *
 * <p>把 X 加密后单独存放在 {@code ai_operation_secret}（AES-256-GCM，密钥由
 * {@code guarantee.auth.jwt.secret} 派生），只在**确认执行的那一刻**解密一次，
 * 执行完成或提案过期即删除。这样即使是 SQL 直查也读不出明文，
 * 而"审计里只有字段名、没有值"的规则仍然严格成立。</p>
 *
 * <p>密钥版本字段预留给后续轮换：轮换时旧密文仍可按版本解密，不需要一次性重写全表。</p>
 */
@Service
public class ProposalSecretStore {

    private static final Logger log = LoggerFactory.getLogger(ProposalSecretStore.class);

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_VERSION = 1;

    private final ProposalSecretMapper secretMapper;
    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public ProposalSecretStore(ProposalSecretMapper secretMapper,
                               @Value("${guarantee.auth.jwt.secret}") String masterSecret) {
        this.secretMapper = secretMapper;
        this.keySpec = deriveKey(masterSecret);
    }

    /** 由主密钥派生 32 字节 AES 密钥，避免直接使用原始长度不一致的字符串。 */
    private static SecretKeySpec deriveKey(String masterSecret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] key = digest.digest(("guarantee-proposal-secret:" + masterSecret)
                    .getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "AES");
        } catch (Exception ex) {
            throw new IllegalStateException("初始化提案密钥失败", ex);
        }
    }

    /**
     * 保存敏感参数密文（与提案同生命周期）。
     *
     * @param proposalId 提案主键
     * @param plainJson  敏感参数的 JSON 原文
     * @param expiresAt  与提案一致的过期时间
     */
    @Transactional
    public void save(Long proposalId, String plainJson, LocalDateTime expiresAt) {
        if (plainJson == null || plainJson.isBlank()) {
            return;
        }
        String cipherText = encrypt(plainJson);
        secretMapper.upsert(proposalId, cipherText, KEY_VERSION, expiresAt);
        log.debug("提案敏感参数已加密暂存 proposalId={}", proposalId);
    }

    /**
     * 读取并解密敏感参数。
     *
     * <p>返回 null 表示"没有敏感参数"或"已过期/已清理"，调用方必须按"缺失"处理，
     * 不允许退化成写入空值。</p>
     */
    @Transactional(readOnly = true)
    public String load(Long proposalId) {
        var entity = secretMapper.selectByProposalId(proposalId);
        if (entity == null) {
            return null;
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.info("提案 {} 的敏感参数已过期，不再解密", proposalId);
            return null;
        }
        try {
            return decrypt(entity.getCipherText());
        } catch (RuntimeException ex) {
            log.error("解密提案敏感参数失败 proposalId={}", proposalId, ex);
            return null;
        }
    }

    /** 执行完成/拒绝/过期后清理密文，尽量缩短敏感数据的存在窗口。 */
    @Transactional
    public void purge(Long proposalId) {
        int deleted = secretMapper.deleteByProposalId(proposalId);
        if (deleted > 0) {
            log.debug("提案敏感参数已清理 proposalId={}", proposalId);
        }
    }

    /** 定时任务：清理过期密文（与提案过期清理同批执行）。 */
    @Transactional
    public int purgeExpired(LocalDateTime now) {
        return secretMapper.deleteExpired(now);
    }

    private String encrypt(String plain) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new IllegalStateException("加密提案敏感参数失败", ex);
        }
    }

    private String decrypt(String cipherText) {
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(combined, GCM_IV_LENGTH, combined.length - GCM_IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("解密失败", ex);
        }
    }
}
