// =====================================================================================================================
// groceryFrontendPipeline - the web app (frontend/grocery-web, contract Phase 14 §6 "Frontend pipeline"):
// npm ci -> lint -> type check -> unit tests (Vitest, JUnit) -> build (Vite) -> Trivy dependency/secret/config scan ->
// production image (Dockerfile.prod: nginx-unprivileged, for non-AWS targets; ECR grocery/grocery-web) + SBOM +
// image scan + push -> deploy dev (S3 releases/<sha>/, CloudFront origin path + invalidation: deploy-spa.sh) ->
// E2E (dev) -> approval -> staging (+ E2E, ZAP baseline) -> approval -> prod (+ post-deploy check).
// On failure after CloudFront was switched: rollback.sh <env> frontend (back to the previous release).
//
//   @Library('grocery') _
//   groceryFrontendPipeline(app: 'grocery-web', dir: 'frontend/grocery-web')
//
// typeCheck: false skips the tsc stage (JavaScript app). publish: false stops after the image scan: no push, no deploy,
// no approvals - for a second copy of the app (frontend/grocery-web-ui) that must not replace the deployed SPA.
// =====================================================================================================================
import com.grocery.ci.Config

def call(Map config = [:]) {
    Map cfg = Config.frontend(config)

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
                    description: 'Print every AWS call (S3, CloudFront, SSM, ECR) instead of making it. Always on when GROCERY_FORCE_DRY_RUN=true.')
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
                                groceryCi.prepareWorkspace(cfg.dir)
                                groceryCi.stashTools(cfg.dir)
                            }
                        }
                    }
                    stage('Install (npm ci)') {
                        steps {
                            script {
                                groceryCi.ensureVolumes([groceryCi.npmVolume()])
                                npm(cfg, 'install', 'npm ci --no-audit --no-fund --loglevel=error')
                            }
                        }
                    }
                    stage('Lint') {
                        steps {
                            script { npm(cfg, 'lint', 'npm run lint') }
                        }
                    }
                    stage('Type check') {
                        when { expression { return cfg.typeCheck } }
                        steps {
                            script { npm(cfg, 'typecheck', 'npx tsc -b --pretty false') }
                        }
                    }
                    stage('Unit tests') {
                        steps {
                            script {
                                npm(cfg, 'unit-tests', 'npx vitest run --reporter=default --reporter=junit --outputFile.junit=reports/junit.xml')
                            }
                        }
                        post {
                            always {
                                junit allowEmptyResults: true, testResults: "${cfg.dir}/reports/junit.xml"
                            }
                        }
                    }
                    stage('Build') {
                        steps {
                            script {
                                npm(cfg, 'build', 'npm run build && find dist -type f | wc -l && du -sh dist')
                                archiveArtifacts artifacts: "${cfg.dir}/dist/**", fingerprint: true
                                stash name: 'spa-dist', includes: "${cfg.dir}/dist/**"
                            }
                        }
                    }
                    stage('Dependency, secret and config scan') {
                        steps {
                            script { grocerySecurity.scanSources(cfg.app, cfg.dir, cfg.dir, [cfg.dir]) }
                        }
                    }
                    stage('Image and SBOM') {
                        steps {
                            script {
                                groceryImage.build(unit: cfg.app, dockerfile: "${cfg.dir}/Dockerfile.prod", context: cfg.dir,
                                        platform: cfg.platform)
                            }
                        }
                    }
                    stage('Image scan') {
                        steps {
                            script { grocerySecurity.scanImage(cfg.app, env.GROCERY_IMAGE, cfg.dir) }
                        }
                    }
                    stage('Push') {
                        when { expression { return cfg.publish } }
                        steps {
                            script { groceryImage.push(cfg.app) }
                        }
                    }
                    stage('Deploy dev') {
                        when { expression { return cfg.publish } }
                        steps {
                            script {
                                groceryDeploy.spa('dev', "${cfg.dir}/dist")
                                groceryImage.promote(cfg.app, 'dev')
                            }
                        }
                    }
                    stage('E2E (dev)') {
                        when { expression { return cfg.publish } }
                        steps {
                            script {
                                groceryTests.e2e('dev')
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
                    expression { return !groceryCi.validateOnly() && cfg.publish && cfg.environments.contains('staging') }
                }
                input {
                    message 'Publish this release to staging?'
                    id 'promote-staging'
                    ok 'Deploy to staging'
                    submitter "${cfg.approvers}"
                }
                steps {
                    echo "staging approved for release ${env.GROCERY_REVISION}"
                }
            }
            stage('Staging') {
                when {
                    beforeAgent true
                    expression { return !groceryCi.validateOnly() && cfg.publish && cfg.environments.contains('staging') }
                }
                agent { label "${cfg.agentLabel}" }
                stages {
                    stage('Deploy staging') {
                        steps {
                            script {
                                groceryCi.unstashTools()
                                unstash 'spa-dist'
                                groceryDeploy.spa('staging', "${cfg.dir}/dist")
                                groceryImage.promote(cfg.app, 'staging')
                            }
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
                    expression { return !groceryCi.validateOnly() && cfg.publish && cfg.environments.contains('prod') }
                }
                input {
                    message 'Publish this release to production?'
                    id 'promote-prod'
                    ok 'Deploy to production'
                    submitter "${cfg.approvers}"
                }
                steps {
                    echo "production approved for release ${env.GROCERY_REVISION}"
                }
            }
            stage('Production') {
                when {
                    beforeAgent true
                    expression { return !groceryCi.validateOnly() && cfg.publish && cfg.environments.contains('prod') }
                }
                agent { label "${cfg.agentLabel}" }
                stages {
                    stage('Deploy prod') {
                        steps {
                            script {
                                groceryCi.unstashTools()
                                unstash 'spa-dist'
                                groceryDeploy.spa('prod', "${cfg.dir}/dist")
                                groceryImage.promote(cfg.app, 'prod')
                            }
                        }
                    }
                    stage('Post-deploy check (prod)') {
                        steps {
                            script {
                                groceryTests.appResponds('prod')
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
                    groceryDeploy.rollbackOnFailure(cfg, 'frontend')
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

/** npm in node (pinned) with the shared npm cache volume, inside the app folder. */
void npm(Map cfg, String name, String command) {
    groceryCi.inContainer(image: cfg.nodeImage, name: "npm-${name}", memory: cfg.npmMemory, workdir: cfg.dir,
            volumes: ["${groceryCi.npmVolume()}:/npm-cache"],
            env: [npm_config_cache: '/npm-cache', NPM_CONFIG_UPDATE_NOTIFIER: 'false', NODE_OPTIONS: '--max-old-space-size=2048'],
            script: command)
}
