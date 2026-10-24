package com.guarantee.ai.knowledge;

import com.guarantee.ai.knowledge.entity.AiKnowledgeItem;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Diagnostic run against packaged sources, without a model or database. */
class RetrievalProtectionProbeTest {
    @Test
    void recordParaphraseRetrieval() {
        var mapper = new InMemoryKnowledgeItemMapper();
        for (var doc : new KnowledgeSourceLoader().load().documents()) {
            var item = new AiKnowledgeItem();
            item.setKnowledgeNo(doc.knowledgeNo());
            item.setDomain(doc.domain());
            item.setTitle(doc.title());
            item.setContent(doc.content());
            item.setKeywords(doc.keywordsText());
            item.setVersion(doc.version());
            item.setStatus(doc.status());
            item.setPermissionCode(doc.permissionCode());
            item.setEffectiveFrom(doc.effectiveFrom());
            item.setEffectiveTo(doc.effectiveTo());
            mapper.seed(item);
        }
        var service = new KnowledgeService(mapper, new KnowledgeProperties());
        String[][] cases = {
            {"部门停用有什么条件", "KB-SYSTEM-0005"},
            {"科室下面还有在职人员，能暂停使用吗", "KB-SYSTEM-0005"},
            {"停用与删除的区别", "KB-SYSTEM-0011"},
            {"暂时不用和彻底移除有什么不同", "KB-SYSTEM-0011"},
            {"保额区间不填是什么意思", "KB-ORDER-0001"},
            {"承保额度上下界留空会限制接单吗", "KB-ORDER-0001"},
            {"金额保留几位小数", "KB-ORDER-0004"},
            {"钱数显示到角还是分", "KB-ORDER-0004"},
            {"逻辑删除后可以恢复吗", "KB-SYSTEM-0009"},
            {"删掉之后去哪找回", "KB-SYSTEM-0009"},
            {"删除有哪些前置检查", "KB-SYSTEM-0012"},
            {"部门还有子部门能停用吗", "KB-SYSTEM-0005"},
            {"部门没有子部门但还有启用用户，能停用吗", "KB-SYSTEM-0005"},
            {"部门没有启用用户但还有下级，能停用吗", "KB-SYSTEM-0005"},
            {"保函金额与保费有什么区别", "KB-CONCEPT-0001"},
            {"担保额度就是我要交的钱吗", "KB-CONCEPT-0001"},
            {"地区编码如何前缀匹配", "KB-ORDER-0002"},
            {"查广东是不是包含下面的市", "KB-ORDER-0002"},
            {"机构层级有哪些", "KB-SYSTEM-0001"},
            {"机构是不是外部出函机构", "KB-SYSTEM-0002"},
            {"数据范围是什么", "KB-SYSTEM-0003"},
            {"为什么我只能看到自己部门的数据", "KB-SYSTEM-0003"},
            {"上个月怎么计算", "KB-ORDER-0005"},
            {"电子保函是什么", "KB-CONCEPT-0002"},
            {"保证金退还流程是怎样的", "NONE"},
            {"保证金专户的规定是什么", "NONE"},
            {"申请保函需要哪些材料", "NONE"},
            {"个人申请保函有哪些办理条件", "NONE"},
            {"操作审计记录的内容与渠道", "NONE"},
            {"退保以后多久到账", "NONE"}
        };
        for (var c : cases) {
            var result = service.search(List.of(), c[0], null, 3);
            var ids = result.items().stream().map(KnowledgeHit::knowledgeNo).toList();
            System.out.println("RETRIEVAL_PROBE | " + c[0] + " | expected=" + c[1]
                    + " | actual=" + ids + " | hit=" + ("NONE".equals(c[1]) ? ids.isEmpty() : ids.contains(c[1])));
        }
        assertThat(service.search(List.of(), "金额保留几位小数", null, 3).items()).isNotEmpty();
        assertThat(service.search(List.of(), "钱数显示到角还是分", null, 3).items())
                .extracting(KnowledgeHit::knowledgeNo).contains("KB-ORDER-0004");
        for (String question : List.of("保证金专户的规定是什么", "申请保函需要哪些材料", "个人申请保函有哪些办理条件")) {
            assertThat(service.search(List.of(), question, null, 3).items()).as(question).isEmpty();
        }
        assertThat(service.search(List.of(), "操作审计记录的内容与渠道", null, 3).items()).isEmpty();
        assertThat(service.search(List.of("system:audit:view"), "操作审计记录的内容与渠道", null, 3).items())
                .extracting(KnowledgeHit::knowledgeNo).contains("KB-SYSTEM-0010");
    }
}
