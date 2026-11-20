// =====================================================================================================================
// groceryDebeziumPluginPipeline - the MSK Connect custom plugin ZIP (Terraform modules/msk-connect): the Debezium
// MySQL connector plugin (Maven Central, SHA-1 pinned) + aws-samples/msk-config-providers (SHA-256 pinned; the
// Secrets Manager config provider the connectors use for the database credentials). Downloaded, verified, assembled,
// scanned (Trivy, SBOM), archived, then uploaded per environment to
//   s3://<SSM /grocery/<env>/shared/msk-connect-plugin-bucket>/<objectKey>
// with approvals before staging and prod. An object already there with the same SHA-256 is not uploaded again.
//
//   @Library('grocery') _
//   groceryDebeziumPluginPipeline()
// =====================================================================================================================
import com.grocery.ci.Config
import com.grocery.ci.Shell

def call(Map config = [:]) {
    Map cfg = Config.debeziumPlugin(config)

    pipeline {
        agent none
        options {
            buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '5'))
            disableConcurrentBuilds()
            timestamps()
            timeout(time: 12, unit: 'HOURS')
            skipDefaultCheckout()
        }
        parameters {
            booleanParam(name: 'DRY_RUN', defaultValue: false,
                    description: 'Print the SSM / S3 calls instead of making them. Always on when GROCERY_FORCE_DRY_RUN=true.')
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
                                groceryCi.prepareWorkspace('infrastructure/ci/jenkins/pipelines/debezium-plugin', cfg.debeziumVersion)
                                groceryCi.stashTools('infrastructure/ci/jenkins/pipelines/debezium-plugin')
                            }
                        }
                    }
                    stage('Download and verify') {
                        steps {
                            script { download(cfg) }
                        }
                    }
                    stage('Assemble ZIP') {
                        steps {
                            script { assemble(cfg) }
                        }
                    }
                    stage('Plugin scan and SBOM') {
                        steps {
                            script { grocerySecurity.scanDirectory('debezium-plugin', '.ci/plugin/zip') }
                        }
                    }
                    stage('Upload dev') {
                        steps {
                            script { upload(cfg, 'dev') }
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
                    message 'Upload this Debezium plugin ZIP to staging?'
                    id 'promote-staging'
                    ok 'Upload to staging'
                    submitter "${cfg.approvers}"
                }
                steps {
                    echo 'staging approved'
                }
            }
            stage('Upload staging') {
                when {
                    beforeAgent true
                    expression { return !groceryCi.validateOnly() && cfg.environments.contains('staging') }
                }
                agent { label "${cfg.agentLabel}" }
                steps {
                    script {
                        groceryCi.unstashTools()
                        unstash 'debezium-plugin-zip'
                        upload(cfg, 'staging')
                        groceryCi.archiveReports('staging')
                    }
                }
            }
            stage('Approve production') {
                when {
                    beforeInput true
                    expression { return !groceryCi.validateOnly() && cfg.environments.contains('prod') }
                }
                input {
                    message 'Upload this Debezium plugin ZIP to production?'
                    id 'promote-prod'
                    ok 'Upload to production'
                    submitter "${cfg.approvers}"
                }
                steps {
                    echo 'production approved'
                }
            }
            stage('Upload prod') {
                when {
                    beforeAgent true
                    expression { return !groceryCi.validateOnly() && cfg.environments.contains('prod') }
                }
                agent { label "${cfg.agentLabel}" }
                steps {
                    script {
                        groceryCi.unstashTools()
                        unstash 'debezium-plugin-zip'
                        upload(cfg, 'prod')
                        groceryCi.archiveReports('prod')
                    }
                }
            }
        }
        post {
            success {
                script { groceryCi.notify(cfg, 'SUCCESS', "Debezium ${cfg.debeziumVersion} plugin ZIP") }
            }
            failure {
                script { groceryCi.notify(cfg, 'FAILURE') }
            }
        }
    }
}

String zipName(Map cfg) {
    return cfg.objectKey.tokenize('/').last()
}

void download(Map cfg) {
    String v = cfg.debeziumVersion
    String url = "https://repo1.maven.org/maven2/io/debezium/debezium-connector-mysql/${v}/debezium-connector-mysql-${v}-plugin.tar.gz"
    sh(label: 'download + checksums', script: """#!/bin/bash
set -euo pipefail
mkdir -p .ci/plugin/download && cd .ci/plugin/download
curl -fsSL --retry 3 -o debezium-plugin.tar.gz ${Shell.quote(url)}
echo ${Shell.quote(cfg.debeziumPluginSha1 + '  debezium-plugin.tar.gz')} | sha1sum -c -
curl -fsSL --retry 3 -o msk-config-providers.jar ${Shell.quote(cfg.configProvidersUrl)}
echo ${Shell.quote(cfg.configProvidersSha256 + '  msk-config-providers.jar')} | sha256sum -c -
ls -l
""")
}

void assemble(Map cfg) {
    String zip = ".ci/plugin/${zipName(cfg)}"
    sh(label: 'assemble', script: """#!/bin/bash
set -euo pipefail
rm -rf .ci/plugin/zip && mkdir -p .ci/plugin/zip
tar -xzf .ci/plugin/download/debezium-plugin.tar.gz -C .ci/plugin/zip
dir=\$(ls .ci/plugin/zip)
[ "\$dir" = debezium-connector-mysql ] || { echo "unexpected plugin layout: \$dir"; exit 1; }
cp .ci/plugin/download/msk-config-providers.jar .ci/plugin/zip/debezium-connector-mysql/
ls .ci/plugin/zip/debezium-connector-mysql/debezium-core-*.jar >/dev/null   # BinaryDataConverter lives there
rm -f ${zip}
(cd .ci/plugin/zip && python3 -m zipfile -c ../${zipName(cfg)} debezium-connector-mysql)
sha256sum ${zip} | tee ${zip}.sha256
python3 -m zipfile -l ${zip} | tail -n +2 | wc -l | xargs echo "entries:"
""")
    archiveArtifacts artifacts: "${zip},${zip}.sha256", fingerprint: true
    stash name: 'debezium-plugin-zip', includes: "${zip},${zip}.sha256"
}

void upload(Map cfg, String envName) {
    String zip = ".ci/plugin/${zipName(cfg)}"
    String sha = readFile("${zip}.sha256").tokenize(' ')[0]
    groceryCi.withAws(groceryCi.deployerRole(envName)) {
        String bucket = groceryCi.aws(['ssm', 'get-parameter', '--name', "/grocery/${envName}/shared/msk-connect-plugin-bucket",
                                       '--query', 'Parameter.Value', '--output', 'text'],
                [sample: "grocery-${envName}-msk-connect-plugins-${groceryCi.account()}"])
        List head = ['s3api', 'head-object', '--bucket', bucket, '--key', cfg.objectKey]
        String existing = 'None'
        if (groceryCi.isDryRun() || groceryCi.aws(head, [status: true]) == '0') {   // 404 when not uploaded yet
            existing = groceryCi.aws(head + ['--query', 'Metadata.sha256', '--output', 'text'], [sample: 'None'])
        }
        if (existing == sha) {
            echo "s3://${bucket}/${cfg.objectKey} already has this ZIP (sha256 ${sha}): not uploaded again"
            return
        }
        groceryCi.aws(['s3', 'cp', zip, "s3://${bucket}/${cfg.objectKey}", '--metadata', "sha256=${sha}",
                       '--content-type', 'application/zip', '--only-show-errors'])
        echo "uploaded ${zipName(cfg)} (sha256 ${sha}) to s3://${bucket}/${cfg.objectKey}; MSK Connect picks it up on the next " +
                "terraform apply with debezium_connectors_enabled = true (envs/${envName})"
    }
}
