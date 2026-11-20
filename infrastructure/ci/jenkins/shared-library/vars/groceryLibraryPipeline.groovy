// =====================================================================================================================
// groceryLibraryPipeline - a shared Maven library (libs/*, contract Phase 14 §6): build and test, compatibility gate
// (event schemas), Trivy scan, publish to CodeArtifact (domain grocery, repository grocery-maven). For
// grocery-event-schemas also the schema-init image (grocery/schema-init:<libVersion>) that registers exactly these
// schemas in each environment before a producer deploys.
//
//   @Library('grocery') _
//   groceryLibraryPipeline(library: 'grocery-event-schemas', schemas: true)
//
// Released versions are immutable: a version already in CodeArtifact / ECR is not published again (bump it).
// DRY_RUN: the library is installed into the CI Maven repository (the local stand-in for CodeArtifact that the
// service pipelines read) and the CodeArtifact / ECR calls are printed.
// =====================================================================================================================
import com.grocery.ci.Config
import com.grocery.ci.Shell

def call(Map config = [:]) {
    Map cfg = Config.library(config)

    pipeline {
        agent none
        options {
            buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
            disableConcurrentBuilds()
            timestamps()
            timeout(time: 4, unit: 'HOURS')
            skipDefaultCheckout()
        }
        parameters {
            booleanParam(name: 'DRY_RUN', defaultValue: false,
                    description: 'Print the CodeArtifact / ECR calls instead of making them; install into the CI Maven repository. Always on when GROCERY_FORCE_DRY_RUN=true.')
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
                            script { groceryCi.prepareWorkspace(cfg.dir) }
                        }
                    }
                    stage('Build and tests') {
                        steps {
                            script { buildAndTest(cfg) }
                        }
                        post {
                            always {
                                script { groceryServicePipeline.publishTests(cfg.dir) }
                            }
                        }
                    }
                    stage('Compatibility gate (schemas)') {
                        when { expression { return cfg.schemas } }
                        steps {
                            script { compatibilityGate(cfg) }
                        }
                    }
                    stage('Dependency, secret and config scan') {
                        steps {
                            script { grocerySecurity.scanSources(cfg.library, cfg.dir, cfg.dir, [cfg.dir]) }
                        }
                    }
                    stage('Publish to CodeArtifact') {
                        steps {
                            script { publish(cfg) }
                        }
                    }
                    stage('schema-init image') {
                        when { expression { return cfg.schemas } }
                        steps {
                            script {
                                groceryImage.build(unit: 'schema-init', dockerfile: 'infrastructure/ci/docker/Dockerfile.schema-init',
                                        context: '.', platform: cfg.platform, extraTags: [env.GROCERY_SEMVER])
                                sh "docker run --rm --entrypoint sh ${env.GROCERY_IMAGE} -c 'ls /schemas | wc -l; ls /schemas/*/*.avsc | wc -l'"
                            }
                        }
                    }
                    stage('schema-init scan') {
                        when { expression { return cfg.schemas } }
                        steps {
                            script { grocerySecurity.scanImage('schema-init', env.GROCERY_IMAGE, cfg.dir) }
                        }
                    }
                    stage('Push schema-init') {
                        when { expression { return cfg.schemas } }
                        steps {
                            script { groceryImage.push('schema-init') }
                        }
                    }
                }
                post {
                    always {
                        script { groceryCi.archiveReports() }
                    }
                }
            }
        }
        post {
            success {
                script { groceryCi.notify(cfg, 'SUCCESS', "${cfg.library} ${env.GROCERY_VERSION ?: ''}") }
            }
            failure {
                script { groceryCi.notify(cfg, 'FAILURE') }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------- stage helpers
void buildAndTest(Map cfg) {
    groceryCi.ensureVolumes([groceryCi.m2Volume()])
    String jacoco = "org.jacoco:jacoco-maven-plugin:${Config.JACOCO_VERSION}"
    String settings = (groceryCi.isLocal() || groceryCi.isDryRun()) ? '' : codeArtifactSettings()
    String deps = ''
    if (cfg.library == 'grocery-common' && (groceryCi.isLocal() || groceryCi.isDryRun())) {
        // grocery-common's tests read the central schemas: install the event-schemas jar it pins from this checkout
        deps = 'mvn -q -f libs/grocery-event-schemas/pom.xml -DskipTests install'
    }
    Closure run = {
        groceryCi.maven(name: "build-${cfg.library}", image: cfg.mavenImage, memory: cfg.mavenMemory, dockerSocket: true,
                passEnv: settings ? ['CODEARTIFACT_AUTH_TOKEN', 'CODEARTIFACT_REPO_URL'] : [],
                script: """
            ${deps}
            mvn ${settings} -f ${cfg.dir}/pom.xml ${jacoco}:prepare-agent verify ${jacoco}:report
            """)
    }
    if (settings) {
        withCodeArtifact { run() }
    } else {
        run()
    }
}

/**
 * The BACKWARD_TRANSITIVE gate of grocery-event-schemas (Confluent's own CompatibilityChecker against the released
 * baselines). It ran with the tests already; here it runs again on its own so the gate has its own stage result.
 */
void compatibilityGate(Map cfg) {
    groceryCi.maven(name: 'compatibility-gate', image: cfg.mavenImage, memory: '1g', script: """
        mvn -o -f ${cfg.dir}/pom.xml surefire:test -Dtest='BaselineCompatibilityTest,CompatibilityGateRulesTest' \\
            -Dsurefire.failIfNoSpecifiedTests=true
        """)
    junit testResults: "${cfg.dir}/target/surefire-reports/TEST-*Compatibility*.xml"
}

void publish(Map cfg) {
    String version = env.GROCERY_VERSION
    if (version.endsWith('-SNAPSHOT')) {
        error("${cfg.library} ${version}: a SNAPSHOT is never published to CodeArtifact (services pin released versions)")
    }
    String owner = groceryCi.account()
    List exists = ['codeartifact', 'list-package-versions', '--domain', 'grocery', '--domain-owner', owner,
                   '--repository', 'grocery-maven', '--format', 'maven', '--namespace', 'com.grocery',
                   '--package', cfg.library, '--query', "versions[?version=='${version}'].version", '--output', 'text']
    if (groceryCi.isDryRun() || groceryCi.isLocal()) {
        groceryCi.withAws('grocery-ci') {
            groceryCi.aws(exists, [sample: ''])
            groceryCi.aws(['codeartifact', 'get-authorization-token', '--domain', 'grocery', '--domain-owner', owner,
                           '--query', 'authorizationToken', '--output', 'text'], [sample: '<token>'])
            String deploy = "mvn -s maven-settings-codeartifact.xml -f ${cfg.dir}/pom.xml deploy -DskipTests " +
                    "-DaltDeploymentRepository=codeartifact::https://grocery-${owner}.d.codeartifact.${groceryCi.region()}.amazonaws.com/maven/grocery-maven/"
            echo "DRY-RUN: ${deploy}"
            groceryCi.record(deploy)
        }
        // the local stand-in for CodeArtifact: the CI Maven repository the service pipelines resolve from
        groceryCi.maven(name: "install-${cfg.library}", image: cfg.mavenImage, memory: '1g',
                script: "mvn -q -f ${cfg.dir}/pom.xml -DskipTests install && echo \"installed com.grocery:${cfg.library}:${version} into the CI Maven repository\"")
        return
    }
    withCodeArtifact {
        String found = groceryCi.aws(exists)
        if (found == version) {
            echo "com.grocery:${cfg.library}:${version} is already in CodeArtifact (released versions are immutable): not published again"
            return
        }
        String settings = codeArtifactSettings()
        groceryCi.maven(name: "deploy-${cfg.library}", image: cfg.mavenImage, memory: '1g',
                passEnv: ['CODEARTIFACT_AUTH_TOKEN', 'CODEARTIFACT_REPO_URL'],
                script: "mvn ${settings} -f ${cfg.dir}/pom.xml deploy -DskipTests -DaltDeploymentRepository=codeartifact::\${CODEARTIFACT_REPO_URL}")
    }
}

// --------------------------------------------------------------------------------------------- CodeArtifact
/** Writes .ci/maven-settings.xml and returns the Maven option that uses it. */
String codeArtifactSettings() {
    writeFile file: '.ci/maven-settings.xml', text: libraryResource('com/grocery/ci/maven-settings-codeartifact.xml')
    return "-s ${env.WORKSPACE}/.ci/maven-settings.xml"
}

/** CODEARTIFACT_REPO_URL and CODEARTIFACT_AUTH_TOKEN (12 h) for Maven, through the grocery-ci role. */
def withCodeArtifact(Closure body) {
    String owner = groceryCi.account()
    groceryCi.withAws('grocery-ci') {
        String url = groceryCi.aws(['codeartifact', 'get-repository-endpoint', '--domain', 'grocery', '--domain-owner', owner,
                                    '--repository', 'grocery-maven', '--format', 'maven', '--query', 'repositoryEndpoint', '--output', 'text'])
        String token = sh(returnStdout: true, label: 'CodeArtifact token', script: Shell.join(['aws', 'codeartifact',
                'get-authorization-token', '--domain', 'grocery', '--domain-owner', owner, '--query', 'authorizationToken', '--output', 'text']) + ' 2>/dev/null').trim()
        withEnv(["CODEARTIFACT_REPO_URL=${url}", "CODEARTIFACT_AUTH_TOKEN=${token}"]) {
            body()
        }
    }
}
