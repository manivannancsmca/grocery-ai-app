package com.grocery.apitests.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import static com.grocery.apitests.support.Json.obj;

/**
 * Registered globally (META-INF/services + autodetection). Puts one resource into the ROOT store, which JUnit closes
 * once, when the whole run is over: every order this run placed that is still open is cancelled by an admin (as the
 * smoke tests do), so no stuck-order alerts or payment expiries follow a run. Delivered, rejected or already
 * cancelled orders answer 409 and are left alone.
 */
public class RunCleanupExtension implements BeforeAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(RunCleanupExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent("cleanup", key -> new Cleanup(), Cleanup.class);
    }

    static final class Cleanup implements AutoCloseable {

        @Override
        public void close() {
            World.partnerIfCreated().ifPresent(World.Partner::offline);
            if (World.placedOrders().isEmpty()) {
                return;
            }
            int cancelled = 0;
            try {
                Session admin = World.admin();
                for (String orderId : World.placedOrders()) {
                    Res order = Api.as(admin).get("/api/admin/orders/" + orderId);
                    if (order.status() == 200 && Boolean.TRUE.equals(order.bool("adminCancellable"))) {
                        Res r = Api.as(admin).body(obj("reason", "API test cleanup")).post("/api/admin/orders/" + orderId + "/cancel");
                        if (r.status() == 200) {
                            cancelled++;
                        }
                    }
                }
            } catch (RuntimeException | AssertionError e) {
                System.err.println("[api-tests] cleanup failed: " + e);
            }
            System.out.println("[api-tests] run " + Config.RUN + ": " + World.placedOrders().size()
                    + " orders placed, " + cancelled + " still open ones cancelled by the admin");
        }
    }
}
