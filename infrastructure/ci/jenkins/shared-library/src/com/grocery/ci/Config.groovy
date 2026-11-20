package com.grocery.ci

/**
 * The parameters each pipeline accepts, their types and defaults. A Jenkinsfile with an unknown key, a wrong type or
 * a missing required key fails at once with a clear message (a typo must not silently switch a stage off).
 */
class Config implements Serializable {

    private static final long serialVersionUID = 1L

    static final String MAVEN_IMAGE = 'maven:3.9.16-eclipse-temurin-25-noble'
    static final String NODE_IMAGE = 'node:24.22.2-alpine3.23'
    static final String TRIVY_IMAGE = 'aquasec/trivy:0.76.2'
    static final String HELPER_IMAGE = 'alpine:3.24.3'
    static final String JACOCO_VERSION = '0.8.14'
    static final List<String> ENVIRONMENTS = ['dev', 'staging', 'prod']

    // key: [type, default, required]
    static final Map COMMON = [
            agentLabel  : [String, 'docker', false],
            environments: [List, ENVIRONMENTS, false],
            approvers   : [String, '', false],      // `input` submitter (user ids / groups); empty = anyone with Job/Build
            notify      : [String, '', false],      // e-mail recipients; empty = log only
            platform    : [String, 'linux/amd64', false],   // Terraform cpu_architecture X86_64 (ARM64 -> linux/arm64)
    ]

    static final Map SERVICE = COMMON + [
            service        : [String, null, true],
            port           : [Integer, null, true],
            producesEvents : [Boolean, false, false],  // -> register schemas (schema-init) before each deployment
            hasContracts   : [Boolean, false, false],  // -> Pact files / provider verification published and required
            testcontainers : [Boolean, true, false],   // -> Docker socket for the Maven tests
            mavenImage     : [String, MAVEN_IMAGE, false],
            mavenMemory    : [String, '3g', false],
            jreImage       : [String, '', false],      // empty = Dockerfile.service default
            smokeGroups    : [String, 'smoke', false], // tests/api-tests JUnit tag expression after dev / prod
            stagingGroups  : [String, 'smoke', false], // ... and in staging (widen when staging runs the simulator)
    ]

    static final Map FRONTEND = COMMON + [
            app       : [String, 'grocery-web', false],
            dir       : [String, 'frontend/grocery-web', false],
            nodeImage : [String, NODE_IMAGE, false],
            npmMemory : [String, '3g', false],
            typeCheck : [Boolean, true, false],   // false for a JavaScript app (no tsc)
            publish   : [Boolean, true, false],   // false: build and scan only - no image push, no SPA deploy/promotion
    ]

    static final Map LIBRARY = COMMON + [
            library    : [String, null, true],
            dir        : [String, '', false],          // default libs/<library>
            schemas    : [Boolean, false, false],      // event schemas: compatibility gate + schema-init image
            mavenImage : [String, MAVEN_IMAGE, false],
            mavenMemory: [String, '3g', false],
    ]

    static final Map DEBEZIUM_PLUGIN = COMMON + [
            debeziumVersion           : [String, '3.7.1.Final', false],
            debeziumPluginSha1        : [String, '9443ef142785e859e6d1103b082b9107df54ceff', false],
            configProvidersUrl        : [String, 'https://github.com/aws-samples/msk-config-providers/releases/download/v0.5.0/msk-config-providers-0.5.0-all.jar', false],
            configProvidersSha256     : [String, '61d7f66d9d626345dc1b5bbc3ceba4320e25100579b94e824a08fee7cd7e44ee', false],
            // Terraform var.msk_connect.plugin_object_key (modules/platform default)
            objectKey                 : [String, 'debezium-mysql/debezium-connector-mysql-3.7.1.Final-with-msk-config-providers.zip', false],
    ]

    @NonCPS
    static Map validate(String kind, Map raw, Map spec) {
        Map cfg = [:]
        List<String> errors = []
        (raw ?: [:]).each { k, v ->
            if (!spec.containsKey(k)) {
                errors << "unknown key '${k}' (allowed: ${spec.keySet().sort().join(', ')})"
            }
        }
        spec.each { String key, List rule ->
            Class type = rule[0]
            def value = (raw ?: [:]).containsKey(key) ? raw[key] : rule[1]
            if (value == null) {
                if (rule[2]) {
                    errors << "'${key}' is required"
                }
                return
            }
            if (type == Integer && value instanceof Number) {
                value = ((Number) value).intValue()
            } else if (type == String && value instanceof GString) {
                value = value.toString()
            } else if (!type.isInstance(value)) {
                errors << "'${key}' must be a ${type.simpleName}, got ${value.getClass().simpleName} (${value})"
                return
            }
            cfg[key] = value
        }
        if (cfg.service != null && !(cfg.service ==~ /[a-z][a-z0-9-]{1,48}/)) {
            errors << "'service' must look like pricing-service, got '${cfg.service}'"
        }
        if (cfg.port != null && !(cfg.port in 1024..65535)) {
            errors << "'port' must be 1024-65535, got ${cfg.port}"
        }
        if (cfg.environments != null) {
            def unknown = cfg.environments.findAll { !(it in ENVIRONMENTS) }
            if (unknown || cfg.environments.isEmpty() || cfg.environments[0] != 'dev') {
                errors << "'environments' must be a non-empty ordered subset of ${ENVIRONMENTS} starting with dev, got ${cfg.environments}"
            }
        }
        if (errors) {
            throw new IllegalArgumentException("${kind} pipeline configuration: " + errors.join('; '))
        }
        cfg.kind = kind
        return cfg
    }

    @NonCPS
    static Map service(Map raw) { return validate('service', raw, SERVICE) }

    @NonCPS
    static Map frontend(Map raw) { return validate('frontend', raw, FRONTEND) }

    @NonCPS
    static Map library(Map raw) {
        Map cfg = validate('library', raw, LIBRARY)
        if (!cfg.dir) {
            cfg.dir = "libs/${cfg.library}".toString()
        }
        return cfg
    }

    @NonCPS
    static Map debeziumPlugin(Map raw) { return validate('debezium-plugin', raw, DEBEZIUM_PLUGIN) }
}
