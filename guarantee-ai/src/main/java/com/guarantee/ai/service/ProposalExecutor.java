package com.guarantee.ai.service;

import com.guarantee.ai.entity.AiOperationProposal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 提案执行器（SYS-W-00 的 ⑥ 步）。
 *
 * <p><b>为什么单独抽一个端口</b>：{@code ProposalService} 负责提案的状态机与审计，
 * 各域的"怎么改数据"分散在 guarantee-system 的 Service 里。抽成端口后，
 * 新增域（例如 D-2a 的"新建账号"）只需要新增一个实现，不需要改动提案机制——
 * 这正是需求 5.2.2 所要求的"预留扩展点"。</p>
 */
public interface ProposalExecutor {

    /** 本执行器支持的目标类型：USER / ORG / DEPT / ROLE / INSURANCE_TYPE。 */
    String targetType();

    /**
     * 目标不存在（或已被删除）时的指纹哨兵值（REQ-CFG-09）。
     *
     * <p>它与任何真实指纹都不相等，因此"确认前目标被删除"同样会被比对拦下并置
     * {@code INVALIDATED}，而不是因为"取不到目标"静默放行。</p>
     */
    String MISSING_FINGERPRINT = "target-missing";

    /**
     * 执行提案。
     *
     * <p>实现必须是**幂等安全**的：调用方已经用条件更新抢占过执行权，
     * 但实现内部仍应使用条件更新（T-08/T-09），避免并发覆盖。</p>
     *
     * <p>执行期必须**重新判定**业务状态（SYS-C-05）：例如"停用最后一个 ADMIN"
     * 要在确认时重新算一遍，不能沿用生成提案时的结论。</p>
     *
     * @param proposal 已抢占执行权的提案（状态为 EXECUTING）
     * @param request  模型解析后的参数（反序列化自 request_payload）
     * @param context  操作者身份与数据范围
     */
    ProposalExecutionResult execute(AiOperationProposal proposal,
                                    ProposalRequest request,
                                    ProposalExecutionContext context);

    /**
     * 目标**当前状态**的版本指纹（REQ-CFG-09 / T-09 闭环）。
     *
     * <p><b>为什么要由执行器实现</b>：只有域执行器持有"按 id 读取目标实体"的入口
     * （{@code OrgService#getEntityById} 等）。指纹必须来自真实行，不能来自预览快照——
     * 预览是生成提案那一刻的旧值，用它比对等于没比。</p>
     *
     * <p><b>为什么把 {@code updated_at} 纳入字段集合</b>：业务字段可能"改了又改回"
     * （名称 A→B→A），只看业务字段会漏判"确认前确实有人动过目标"；
     * {@code updated_at} 是行级时间戳，任何 UPDATE 都会变，且同一状态重复读取完全一致
     * （不引入随机值或"当前时间"，因此指纹是稳定的）。同时保留业务字段，
     * 是为了让"被改动的是哪一类字段"在日志里可读，而不是只给出一个哈希。</p>
     *
     * <p><b>{@code null} 的语义</b>：该提案没有可比对的目标（{@code CREATE} 类，
     * 或 {@code targetId} 为空）。调用方必须跳过比对，但**不得**因此放宽其它任何校验。</p>
     */
    default String fingerprint(AiOperationProposal proposal, ProposalRequest request) {
        return null;
    }

    /**
     * 目标状态字段 → 稳定指纹（SHA-256 十六进制）。
     *
     * <p>字段之间用不可打印分隔符拼接，避免 {@code ("ab","c")} 与 {@code ("a","bc")}
     * 得到同一个值；{@code null} 也有独立的标记（区别于空串）。</p>
     */
    static String fingerprintHash(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            canonical.append(part == null ? "\u0000<nil>" : part.toString()).append('\u0001');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用，无法计算目标指纹", ex);
        }
    }
}
