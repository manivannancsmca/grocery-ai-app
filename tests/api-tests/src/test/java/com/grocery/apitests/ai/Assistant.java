package com.grocery.apitests.ai;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.Session;
import com.grocery.apitests.support.World;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Shared checks of the assistant tests. */
final class Assistant {

    private static Boolean scripted;

    private Assistant() {
    }

    /** The AI tests need the deterministic {@code scripted} model (AI_PROVIDER=scripted, local/docker profiles only). */
    static synchronized void requireScriptedModel() {
        if (scripted == null) {
            Res status = Api.as(World.admin()).get("/api/ai/status");
            scripted = status.status() == 200 && "scripted".equals(status.str("provider"))
                    && Boolean.TRUE.equals(status.bool("available"));
        }
        assumeTrue(scripted, "ai-orchestrator-service is not running AI_PROVIDER=scripted (see README)");
    }

    /** The audit row of the user's last call of that tool (any admin may read the audit). */
    static Map<String, Object> audited(Session user, String tool) {
        Res audit = Api.as(World.adminWithRole("OPS_ADMIN"))
                .get("/api/admin/ai/tool-invocations?userId=" + user.userId() + "&tool=" + tool + "&size=5").ok().page();
        List<Map<String, Object>> rows = audit.list("content");
        assertThat(rows).as("audit of %s for %s", tool, user).isNotEmpty();
        return rows.getFirst();
    }

    static Res approval(Session user, String id) {
        return Api.as(user).get("/api/approvals/" + id).ok();
    }
}
