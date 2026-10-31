package com.guarantee.ai.tool;

import com.guarantee.common.api.PageResult;
import com.guarantee.system.dto.RoleDto;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.service.RoleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RoleQueryToolTest {
    @Test
    void keywordAndExplicitFiltersRemainIndependent() {
        var service = mock(RoleService.class);
        var resolver = mock(AiDataScopeResolver.class);
        var scope = DataScope.all(null, null);
        when(resolver.resolve(any())).thenReturn(scope);
        when(service.page(any(), eq(scope))).thenReturn(PageResult.empty(1, 20));
        var tool = new RoleQueryTool(service, resolver);
        var context = new ToolContext(Map.of(AiToolContextKeys.PERMISSIONS, List.of("system:role:view")));
        tool.queryRole("ROLE", "EDGE_ACCEPT", null, null, false, 20, context);
        var query = ArgumentCaptor.forClass(RoleDto.Query.class);
        verify(service).page(query.capture(), eq(scope));
        assertThat(query.getValue().getKeyword()).isEqualTo("EDGE_ACCEPT");
        assertThat(query.getValue().getRoleCode()).isNull();
        assertThat(query.getValue().getRoleName()).isNull();
        clearInvocations(service);
        tool.queryRole("ROLE", "名称", " CODE ", 1, false, 20, context);
        verify(service).page(query.capture(), eq(scope));
        assertThat(query.getValue().getRoleCode()).isEqualTo("CODE");
        assertThat(query.getValue().getKeyword()).isEqualTo("名称");
        assertThat(query.getValue().getStatus()).isEqualTo(1);
    }
}
