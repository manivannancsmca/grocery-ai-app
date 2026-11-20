package com.grocery.ai.approval;

import com.grocery.ai.config.AiProperties;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

/** Test access to the package-private key loading of {@link ApprovalSigningKey}. */
public final class ApprovalSigningKeyAccess {

    private ApprovalSigningKeyAccess() {
    }

    public static RSAKey load(String pem) throws Exception {
        return ApprovalSigningKey.fromPem(pem);
    }

    /** The component as the application creates it from {@code AI_APPROVAL_SIGNING_KEY}. */
    public static ApprovalSigningKey create(String pem) {
        AiProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "grocery.ai.approvals.signing-key", pem))).bindOrCreate("grocery.ai", AiProperties.class);
        return new ApprovalSigningKey(properties);
    }
}
