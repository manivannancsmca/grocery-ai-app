// =====================================================================================================================
// groceryServicePipeline - build, test, scan, image and deploy ONE Spring Boot service (contract Phase 14 §6).
//
//   @Library('grocery') _
//   groceryServicePipeline(service: 'pricing-service', port: 8108, producesEvents: true)
//
// Stages: checkout -> build + unit/integration tests (Maven in Docker, Testcontainers through the Docker socket;
// JUnit + JaCoCo) -> contract tests (Pact files / verifications) -> Trivy dependency, secret and config scan ->
// image (infrastructure/ci/docker/Dockerfile.service) + SBOM -> Trivy image scan -> push (ECR) -> register schemas
// (producers) -> deploy dev (ECS blue/green, wait stable) -> API smoke tests (dev) -> manual approval -> staging
// (same image; API tests, E2E, ZAP baseline) -> manual approval -> prod (blue/green with bake + alarms) ->
// post-deploy smoke. On failure after a deployment started: automatic rollback of that environment + notification.
// Configuration keys and defaults: src/com/grocery/ci/Config.groovy (SERVICE).
// =====================================================================================================================
import com.grocery.ci.Config

def call(Map config = [:]) {
    Map cfg = Config.service(config)
    String moduleDir = "services/${cfg.service}"

    pipeline {
        agent none
        options {
            buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
            disableConcurrentBuilds()
            timestamps()
            timeout(time: 12, unit: 'HOURS')
            skipDefaultCheckout()
        }
        parameters {
            booleanParam(name: 'DRY_RUN', defaultValue: false,
                    description: 'Print every AWS call instead of making it: task definitions are rendered from the Terraform catalog, ECR/ECS/SSM calls are printed, images go to the local registry if one is configured. Always on when GROCERY_FORCE_DRY_RUN=true.')
            booleanParam(name: 'VALIDATE_ONLY', defaultValue: false,
                    description: 'Only load the Jenkinsfile and the shared library and validate the configuration; no stage runs.')
        }
        stages {
            stage('Configuration') {
                steps {
                    script { groceryCi.banner(cfg) }
                }
            }
            stage('Build and publish') {
                when {
                    beforeAgent true
                    expression { return !groceryCi.validateOnly() }
                }
                agent { label "${cfg.agentLabel}" }
                stages {
                    stage('Checkout') {
                        steps {
                            script {
                                groceryCi.prepareWorkspace(moduleDir)
                                checkCatalog(cfg)
                                groceryCi.stashTools(moduleDir)
                            }
                        }
                    }
                    stage('Build and unit/integration tests') {
                        steps {
                            script { buildAndTest(cfg, moduleDir) }
                        }
                        post {
                            always {
                                script { publishTests(moduleDir) }
                            }
                        }
                    }
                    stage('Contract tests') {
                        when { expression { return cfg.hasContracts } }
                        steps {
                            script { contracts(moduleDir) }
                        }
                    }
                    stage('Dependency, secret and config scan') {
                        steps {
                            script {
                                grocerySecurity.scanSources(cfg.service, moduleDir, moduleDir, ['infrastructure/ci/docker', moduleDir])
                            }
                        }
                    }
                    stage('Image and SBOM') {
                        steps {
                            script { groceryImage.buildService(cfg, moduleDir) }
                        }
                    }
                    stage('Image scan') {
                        steps {
                            script { grocerySecurity.scanImage(cfg.service, env.GROCERY_IMAGE, moduleDir) }
                        }
                    }
                    stage('Push') {
                        steps {
                            script { groceryImage.push(cfg.service) }
                        }
                    }
                    stage('Register schemas (dev)') {
                        when { expression { return cfg.producesEvents } }
                        steps {
                            script { groceryDeploy.registerSchemas('dev', env.GROCERY_EVENT_SCHEMAS_VERSION) }
                        }
                    }
                    stage('Deploy dev') {
                        steps {
                            script {
                                groceryDeploy.ecs('dev', cfg.service, env.GROCERY_IMAGE_TAG)
                                groceryImage.promote(cfg.service, 'dev')
                            }
                        }
                    }
                    stage('Smoke tests (dev)') {
                        steps {
                            script {
                                groceryTests.api('dev', cfg.smokeGroups)
                                groceryDeploy.verified('dev')
                            }
                        }
                    }
                }
                post {
                    always {
                        script { groceryCi.archiveReports('build') }
                    }
                }
            }
            stage('Approve staging') {
                when {
                    beforeInput true
                    expression { return !groceryCi.validateOnly() && cfg.environments.contains('staging') }
                }
                input {
                    message 'Promote this image to staging?'
                    id 'promote-staging'
                    ok 'Deploy to staging'
                    submitter "${cfg.approvers}"
                }
                steps {
                    echo "staging approved for ${env.GROCERY_IMAGE_TAG}"
                }
            }
            stage('Staging') {
                when {
                    beforeAgent true
                    expression { return !groceryCi.validateOnly() && cfg.environments.contains('staging') }
                }
                agent { label "${cfg.agentLabel}" }
                stages {
                    stage('Register schemas (staging)') {
                        when { expression { return cfg.producesEvents } }
                        steps {
                            script {
                                groceryCi.unstashTools()
                                groceryDeploy.registerSchemas('staging', env.GROCERY_EVENT_SCHEMAS_VERSION)
                            }
                        }
                    }
                    stage('Deploy staging') {
                        steps {
                            script {
                                if (!cfg.producesEvents) {
                                    groceryCi.unstashTools()
                                }
                                groceryDeploy.ecs('staging', cfg.service, env.GROCERY_IMAGE_TAG)
                                groceryImage.promote(cfg.service, 'staging')
                            }
                        }
                    }
                    stage('API tests (staging)') {
                        steps {
                            script { groceryTests.api('staging', cfg.stagingGroups) }
                        }
                    }
                    stage('E2E (staging)') {
                        steps {
                            script { groceryTests.e2e('staging') }
                        }
                    }
                    stage('ZAP baseline (staging)') {
                        steps {
                            script {
                                groceryTests.zap('staging')
                                groceryDeploy.verified('staging')
                            }
                        }
                    }
                }
                post {
                    always {
                        script { groceryCi.archiveReports('staging') }
                    }
                }
            }
            stage('Approve production') {
                when {
                    beforeInput true
                    expression { return !groceryCi.validateOnly() && cfg.environments.contains('prod') }
                }
                input {
                    message 'Promote this image to production?'
                    id 'promote-prod'
                    ok 'Deploy to production'
                    submitter "${cfg.approvers}"
                }
                steps {
                    echo "production approved for ${env.GROCERY_IMAGE_TAG}"
                }
            }
            stage('Production') {
                when {
                    beforeAgent true
                    expression { return !groceryCi.validateOnly() && cfg.environments.contains('prod') }
                }
                agent { label "${cfg.agentLabel}" }
                stages {
                    stage('Register schemas (prod)') {
                        when { expression { return cfg.producesEvents } }
                        steps {
                            script {
                                groceryCi.unstashTools()
                                groceryDeploy.registerSchemas('prod', env.GROCERY_EVENT_SCHEMAS_VERSION)
                            }
                        }
                    }
                    stage('Deploy prod') {
                        steps {
                            script {
                                if (!cfg.producesEvents) {
                                    groceryCi.unstashTools()
                                }
                                // blue/green with a 5-minute bake and alarm-based rollback (Terraform): allow for it
                                groceryDeploy.ecs('prod', cfg.service, env.GROCERY_IMAGE_TAG, [waitMinutes: 60])
                                groceryImage.promote(cfg.service, 'prod')
                            }
                        }
                    }
                    stage('Post-deploy smoke (prod)') {
                        steps {
                            script {
                                groceryTests.api('prod', cfg.smokeGroups)
                                groceryDeploy.verified('prod')
                            }
                        }
                    }
                }
                post {
                    always {
                        script { groceryCi.archiveReports('prod') }
                    }
                }
            }
        }
        post {
            success {
                script { groceryCi.notify(cfg, 'SUCCESS', "verified: ${env.GROCERY_VERIFIED_ENVS ?: 'none'}") }
            }
            failure {
                script {
                    groceryCi.notify(cfg, 'FAILURE', "deploying: ${env.GROCERY_ACTIVE_ENV ?: 'none'}")
                    groceryDeploy.rollbackOnFailure(cfg, cfg.service)
                }
            }
            unstable {
                script { groceryCi.notify(cfg, 'UNSTABLE') }
            }
            aborted {
                script { groceryCi.notify(cfg, 'ABORTED (approval declined or timed out)', "verified: ${env.GROCERY_VERIFIED_ENVS ?: 'none'}") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------- stage helpers
/** The Jenkinsfile must agree with the Terraform catalog (services.auto.tfvars.json): same unit and port. */
void checkCatalog(Map cfg) {
    String catalogFile = 'infrastructure/aws/terraform/services.auto.tfvars.json'
    if (!fileExists(catalogFile)) {
        echo "WARNING: ${catalogFile} not found - port not cross-checked"
        return
    }
    def unit = readJSON(file: catalogFile).services[cfg.service]
    if (unit == null) {
        error("${cfg.service} is not a unit of ${catalogFile}: no ECR repository / ECS service exists for it")
    }
    if ((unit.port as int) != cfg.port) {
        error("Jenkinsfile port ${cfg.port} != catalog port ${unit.port} for ${cfg.service}")
    }
    boolean hasConnector = unit.debezium_connector != null && unit.debezium_connector.toString() != 'null'
    if (hasConnector && !cfg.producesEvents) {
        echo "WARNING: ${cfg.service} has a Debezium connector (it publishes events) but producesEvents is false: schemas are not registered before its deployments"
    }
    echo "catalog: ${cfg.service} port ${unit.port}, needs ${unit.needs}, repository ${unit.image_repository}"
}

/** Maven verify with JaCoCo (agent + report added on the command line: the poms stay untouched). */
void buildAndTest(Map cfg, String moduleDir) {
    groceryCi.ensureVolumes([groceryCi.m2Volume()])
    String jacoco = "org.jacoco:jacoco-maven-plugin:${Config.JACOCO_VERSION}"
    String libs = ''
    String settings = ''
    if (groceryCi.isLocal() || groceryCi.isDryRun()) {
        // CodeArtifact stand-in: the shared libraries are installed from this checkout into the CI Maven repository
        libs = '''
            echo "--- grocery-event-schemas and grocery-common from source (CodeArtifact stand-in)"
            mvn -q -f libs/grocery-event-schemas/pom.xml -DskipTests install
            mvn -q -f libs/grocery-common/pom.xml -DskipTests install
            '''
    } else {
        settings = groceryLibraryPipeline.codeArtifactSettings()
    }
    Closure run = {
        groceryCi.maven(name: 'build-and-test', image: cfg.mavenImage, memory: cfg.mavenMemory, dockerSocket: cfg.testcontainers,
                passEnv: settings ? ['CODEARTIFACT_AUTH_TOKEN', 'CODEARTIFACT_REPO_URL'] : [],
                script: """
            ${libs}
            echo "--- ${moduleDir}: verify (unit + integration tests${cfg.testcontainers ? ', Testcontainers' : ''}) with JaCoCo"
            mvn ${settings} -f ${moduleDir}/pom.xml ${jacoco}:prepare-agent verify ${jacoco}:report
            """)
    }
    if (settings) {
        groceryLibraryPipeline.withCodeArtifact { run() }
    } else {
        run()
    }
}

void publishTests(String moduleDir) {
    junit allowEmptyResults: true, testResults: "${moduleDir}/target/surefire-reports/*.xml,${moduleDir}/target/failsafe-reports/*.xml"
    if (fileExists("${moduleDir}/target/jacoco.exec")) {
        jacoco(execPattern: "${moduleDir}/target/jacoco.exec", classPattern: "${moduleDir}/target/classes",
                sourcePattern: "${moduleDir}/src/main/java", sourceInclusionPattern: '**/*.java')
    }
    archiveArtifacts artifacts: "${moduleDir}/target/site/jacoco/jacoco.xml", allowEmptyArchive: true
}

/**
 * Pact (file-based, no broker; contract Phase 13 §4). The consumer tests (re)write contracts/pacts/<consumer>-<provider>.json
 * (pact.rootDir in the consumer's pom), the provider tests verify every file there naming them as the provider. Both ran
 * in the build stage; this stage publishes the consumer's pacts, requires that something contract-related ran, and fails
 * when a consumer's regenerated pacts differ from the committed ones: a changed contract must be committed (and so be
 * verified by the providers' own pipelines) before it ships.
 */
void contracts(String moduleDir) {
    String service = moduleDir.tokenize('/').last()
    def pacts = findFiles(glob: "contracts/pacts/${service}-*.json")
    def verifications = findFiles(glob: "${moduleDir}/target/surefire-reports/TEST-*ProviderPact*.xml")
    def consumerRuns = findFiles(glob: "${moduleDir}/target/surefire-reports/TEST-*ConsumerPact*.xml")
    if (consumerRuns.length == 0 && verifications.length == 0) {
        error("hasContracts is set, but ${moduleDir} ran no Pact consumer test and no provider verification")
    }
    if (consumerRuns.length > 0) {
        if (pacts.length == 0) {
            error("${service} ran Pact consumer tests but contracts/pacts/${service}-*.json is missing (pact.rootDir in its pom?)")
        }
        // In a git checkout: the regenerated files must equal the committed ones (untracked = a new, uncommitted contract).
        if (fileExists('.git')) {
            int drift = sh(returnStatus: true, script: """
                git diff --exit-code --stat -- 'contracts/pacts/${service}-*.json' &&
                test -z "\$(git ls-files --others --exclude-standard -- 'contracts/pacts/${service}-*.json')"
            """)
            if (drift != 0) {
                error("${service}'s Pact contracts changed: regenerate them locally, commit contracts/pacts/${service}-*.json and let the providers verify them")
            }
        } else {
            echo "contracts: not a git checkout, the committed-contract check is skipped"
        }
        sh "mkdir -p .ci/reports/pacts && cp contracts/pacts/${service}-*.json .ci/reports/pacts/"
        archiveArtifacts artifacts: '.ci/reports/pacts/*.json', fingerprint: true
    }
    echo "contracts: ${pacts.length} pact file(s) published, ${verifications.length} provider verification report(s)"
}
