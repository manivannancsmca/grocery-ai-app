// =====================================================================================================================
// groceryCi - core helpers of the grocery pipelines: run mode (DRY_RUN / local), checkout, build facts, containers,
// AWS calls, notifications. Used by groceryServicePipeline, groceryFrontendPipeline, groceryLibraryPipeline and
// groceryDebeziumPluginPipeline; see infrastructure/ci/README.md.
//
// Global environment (Jenkins > Manage > System > Global properties, or JCasC):
//   AWS_REGION, AWS_ACCOUNT_ID            target account (DRY_RUN placeholders when unset: ap-south-1, 000000000000)
//   GROCERY_ECR_REGISTRY                  default <account>.dkr.ecr.<region>.amazonaws.com
//   GROCERY_AWS_CREDENTIAL_SOURCE         how the agent gets its base AWS identity for the role profiles:
//                                         Ec2InstanceMetadata (default) | EcsContainer | Environment
//   GROCERY_FORCE_DRY_RUN=true            no AWS call is ever made, whatever the DRY_RUN parameter says
//   GROCERY_LOCAL_CI=true                 the local validation Jenkins (infrastructure/ci/jenkins/local)
//   GROCERY_LOCAL_REPO=/repo              no SCM: the checkout copies this directory (the repo is not a git repo)
//   GROCERY_CI_REGISTRY=localhost:5000    local registry standing in for ECR in DRY_RUN (pushed for real)
//   GROCERY_CI_VOLUMES_FROM=<container>   the controller runs in Docker: step containers get --volumes-from it
//   GROCERY_DOCKER_GID                    group owning the Docker socket (step containers that need Docker)
//   GROCERY_CI_M2_VOLUME / _NPM_VOLUME / _TRIVY_VOLUME   cache volumes (grocery-ci-m2, grocery-ci-npm, ...)
// =====================================================================================================================
import com.grocery.ci.Config
import com.grocery.ci.Shell

// ------------------------------------------------------------------------------------------------------------ mode
boolean isLocal() {
    return env.GROCERY_LOCAL_CI == 'true'
}

boolean isDryRun() {
    return params.DRY_RUN == true || env.GROCERY_FORCE_DRY_RUN == 'true'
}

boolean validateOnly() {
    return params.VALIDATE_ONLY == true
}

String region() { return env.AWS_REGION ?: 'ap-south-1' }

String account() { return env.AWS_ACCOUNT_ID ?: '000000000000' }

String ecrRegistry() { return env.GROCERY_ECR_REGISTRY ?: "${account()}.dkr.ecr.${region()}.amazonaws.com".toString() }

/** The registry images are pushed to in this run: ECR, or (DRY_RUN) the local registry standing in for it. */
String pushRegistry() { return isDryRun() ? (env.GROCERY_CI_REGISTRY ?: '') : ecrRegistry() }

String m2Volume() { return env.GROCERY_CI_M2_VOLUME ?: 'grocery-ci-m2' }

String npmVolume() { return env.GROCERY_CI_NPM_VOLUME ?: 'grocery-ci-npm' }

String trivyVolume() { return env.GROCERY_CI_TRIVY_VOLUME ?: 'grocery-ci-trivy-cache' }

void banner(Map cfg) {
    String mode = isDryRun() ? 'DRY_RUN (AWS calls are printed, not made)' : 'LIVE (AWS calls are made)'
    echo """grocery ${cfg.kind} pipeline
  configuration : ${cfg.findAll { k, v -> k != 'kind' }}
  mode          : ${mode}${isLocal() ? ' - local validation Jenkins' : ''}
  account/region: ${account()} / ${region()}
  ECR registry  : ${ecrRegistry()}${isDryRun() && env.GROCERY_CI_REGISTRY ? " (stand-in: ${env.GROCERY_CI_REGISTRY})" : ''}"""
}

// -------------------------------------------------------------------------------------------------------- checkout
/**
 * Fills the workspace (SCM checkout, or a copy of GROCERY_LOCAL_REPO) and records the build facts in env and
 * .ci/build-info.json: GROCERY_REVISION (git SHA; locally a content hash), GROCERY_SHORT_SHA, GROCERY_VERSION,
 * GROCERY_SEMVER, GROCERY_IMAGE_TAG (<semver>-<shortSha>), GROCERY_SHA_TAG (sha-<fullSha>), GROCERY_CREATED,
 * GROCERY_SOURCE, GROCERY_EVENT_SCHEMAS_VERSION. versionOverride: for artifacts without pom.xml / package.json.
 */
Map prepareWorkspace(String moduleDir, String versionOverride = null) {
    String revision
    String source
    if (env.GROCERY_LOCAL_REPO) {
        sh '''#!/bin/bash
set -euo pipefail
src="$GROCERY_LOCAL_REPO"
[ -d "$src" ] || { echo "GROCERY_LOCAL_REPO=$src is not a directory"; exit 1; }
find . -mindepth 1 -maxdepth 1 -exec rm -rf {} +
# build outputs, dependencies and reports of the developer's tree are not sources
tar -C "$src" --exclude=.git --exclude=node_modules --exclude=target --exclude=dist --exclude=.terraform \
    --exclude=./tests/security/reports --exclude=./tests/performance/reports --exclude=./tests/e2e/test-results \
    --exclude=./tests/e2e/playwright-report --exclude=./infrastructure/aws/.validate     --warning=no-file-changed --warning=no-file-removed -cf - . | tar -xf -
st=("${PIPESTATUS[@]}")
# tar exits 1 when a file changed while it was read (someone is editing the tree): the copy is still complete
[ "${st[0]}" -le 1 ] && [ "${st[1]}" -eq 0 ] || { echo "copy of $src failed (tar ${st[*]})"; exit 1; }
echo "copied $(find . -type f | wc -l) files from $src"
'''
        // No git: the revision is a content hash of the sources (stable while the files do not change).
        revision = sh(returnStdout: true, label: 'content hash (revision)', script: '''#!/bin/bash
set -euo pipefail
find . -type f ! -path './.ci/*' -print0 | LC_ALL=C sort -z | xargs -0 sha1sum | sha1sum | cut -c1-40''').trim()
        source = "file://${env.GROCERY_LOCAL_REPO}".toString()
    } else {
        def scmVars = checkout(scm)
        revision = scmVars?.GIT_COMMIT ?: sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
        source = scmVars?.GIT_URL ?: ''
    }
    if (!(revision ==~ /[0-9a-f]{40}/)) {
        error("cannot determine the revision (got '${revision}')")
    }
    sh 'mkdir -p .ci/home .ci/reports .ci/steps'
    writeFile file: '.ci/module-info.py', text: libraryResource('com/grocery/ci/module-info.py')
    def info = versionOverride ? [version: versionOverride, semver: versionOverride] :
            readJSON(text: sh(returnStdout: true, label: 'module version',
                    script: "python3 .ci/module-info.py ${Shell.quote(moduleDir)}").trim())
    String created = sh(returnStdout: true, script: 'date -u +%Y-%m-%dT%H:%M:%SZ').trim()

    env.GROCERY_REVISION = revision
    env.GROCERY_SHORT_SHA = revision.take(7)
    env.GROCERY_VERSION = info.version.toString()
    env.GROCERY_SEMVER = info.semver.toString()
    env.GROCERY_IMAGE_TAG = "${info.semver}-${revision.take(7)}".toString()
    env.GROCERY_SHA_TAG = "sha-${revision}".toString()
    env.GROCERY_CREATED = created
    env.GROCERY_SOURCE = source
    env.GROCERY_EVENT_SCHEMAS_VERSION = info.eventSchemas ? info.eventSchemas.toString() : ''
    env.GROCERY_MODULE_DIR = moduleDir

    Map facts = [revision     : revision, shortSha: env.GROCERY_SHORT_SHA, version: env.GROCERY_VERSION,
                 imageTag     : env.GROCERY_IMAGE_TAG, shaTag: env.GROCERY_SHA_TAG, created: created,
                 source       : source, module: moduleDir, eventSchemas: env.GROCERY_EVENT_SCHEMAS_VERSION,
                 dryRun       : isDryRun(), build: env.BUILD_TAG]
    writeJSON file: '.ci/build-info.json', json: facts, pretty: 2
    currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.GROCERY_IMAGE_TAG}${isDryRun() ? ' (dry run)' : ''}"
    echo "build facts: ${facts}"
    return facts
}

/** What later agent allocations (staging, prod, post-failure rollback) need: scripts, test suites, build facts. */
void stashTools(String moduleDir) {
    stash name: 'grocery-ci-tools', useDefaultExcludes: false,
            includes: ".ci/build-info.json,.ci/module-info.py,infrastructure/ci/**,infrastructure/local/schema-registry/**," +
                    "infrastructure/aws/terraform/services.auto.tfvars.json,infrastructure/aws/terraform/envs/*/*.tfvars," +
                    "tests/api-tests/**,tests/e2e/**,tests/security/**,${moduleDir}/pom.xml,${moduleDir}/package.json",
            excludes: '**/node_modules/**,**/target/**,tests/security/reports/**,tests/e2e/test-results/**'
}

void unstashTools() {
    sh 'find . -mindepth 1 -maxdepth 1 -exec rm -rf {} +'
    unstash 'grocery-ci-tools'
    sh 'mkdir -p .ci/home .ci/reports .ci/steps'
}

// ------------------------------------------------------------------------------------------------------ containers
String uidGid() {
    if (!env.GROCERY_UID_GID) {
        env.GROCERY_UID_GID = sh(returnStdout: true, script: 'echo "$(id -u):$(id -g)"').trim()
    }
    return env.GROCERY_UID_GID
}

String dockerGid() {
    if (env.GROCERY_DOCKER_GID) {
        return env.GROCERY_DOCKER_GID
    }
    return sh(returnStdout: true, script: 'stat -c %g /var/run/docker.sock').trim()
}

/** Creates the cache volumes when missing and gives their root directory to the agent's user. */
void ensureVolumes(List<String> volumes) {
    String ug = uidGid()
    for (String v in volumes) {
        sh(label: "cache volume ${v}", script: Shell.join(['docker', 'volume', 'create', v]) + ' >/dev/null && ' +
                Shell.join(['docker', 'run', '--rm', '-u', '0', '-v', "${v}:/v", Config.HELPER_IMAGE, 'chown', ug, '/v']))
    }
}

/**
 * Runs a shell script in a throw-away container that sees the workspace at the same path.
 *   image, script (required); name (step label); memory ('2g'); env (Map, values visible on the command line - no
 *   secrets); passEnv (names copied from the step's environment, for credentials); volumes ('vol:/path[:ro]');
 *   dockerSocket (Docker access for Testcontainers / Trivy image scans); network; workdir (relative to the workspace)
 * The container runs as the agent's user, so everything it writes in the workspace stays cleanable.
 */
void inContainer(Map a) {
    String ws = env.WORKSPACE
    String name = Shell.slug(a.name ?: 'step')
    String container = Shell.slug("gci-${env.BUILD_TAG}-${name}").take(63)
    String scriptFile = ".ci/steps/${name}.sh"
    writeFile file: scriptFile, text: "set -eu\n" + a.script.stripIndent().trim() + '\n'
    List cmd = ['docker', 'run', '--rm', '--init', '--name', container, '-u', uidGid(),
                '--memory', a.memory ?: '2g', '-w', a.workdir ? "${ws}/${a.workdir}" : ws,
                '-e', "HOME=${ws}/.ci/home", '-e', 'CI=true', '--entrypoint', 'sh']
    if (env.GROCERY_CI_VOLUMES_FROM) {
        cmd += ['--volumes-from', env.GROCERY_CI_VOLUMES_FROM]
    } else {
        cmd += ['-v', "${ws}:${ws}"]
    }
    if (a.dockerSocket) {
        if (!env.GROCERY_CI_VOLUMES_FROM) {
            cmd += ['-v', '/var/run/docker.sock:/var/run/docker.sock']
        }
        cmd += ['--group-add', dockerGid(), '--add-host', 'host.docker.internal:host-gateway']
    }
    for (String v in (a.volumes ?: [])) {
        cmd += ['-v', v]
    }
    (a.env ?: [:]).each { k, v -> cmd += ['-e', "${k}=${v}"] }
    for (String n in (a.passEnv ?: [])) {
        cmd += ['-e', n]
    }
    if (a.network) {
        cmd += ['--network', a.network]
    }
    cmd += [a.image, "${ws}/${scriptFile}"]
    try {
        sh(label: a.name ?: 'container step', script: Shell.join(cmd))
    } finally {
        // an aborted build kills the docker client, not necessarily the container
        sh(label: 'cleanup', script: "docker rm -f ${Shell.quote(container)} >/dev/null 2>&1 || true")
    }
}

/** Maven in a container with the shared repository volume; MAVEN_OPTS keeps Maven itself small (tests fork). */
void maven(Map a) {
    List<String> volumes = ["${m2Volume()}:/m2"] + (a.volumes ?: [])
    Map envVars = [MAVEN_OPTS: '-Xmx512m -XX:+UseSerialGC', MAVEN_ARGS: '-B -ntp -Dstyle.color=never -Dmaven.repo.local=/m2/repository'] + (a.env ?: [:])
    if (a.dockerSocket) {
        envVars.TESTCONTAINERS_HOST_OVERRIDE = 'host.docker.internal'
    }
    inContainer(image: a.image ?: Config.MAVEN_IMAGE, name: a.name, memory: a.memory ?: '3g', script: a.script,
            env: envVars, passEnv: a.passEnv, volumes: volumes, dockerSocket: a.dockerSocket, network: a.network)
}

// ------------------------------------------------------------------------------------------------------------- AWS
/** Appends a printed/made AWS (or deploy) call to .ci/reports/aws-calls.txt (archived). */
void record(String line) {
    String f = '.ci/reports/aws-calls.txt'
    String previous = fileExists(f) ? readFile(f) : ''
    writeFile file: f, text: previous + line + '\n'
}

/**
 * `aws <args>`: run and return stdout; opts.status: return the exit status ('0' ...) instead; opts.toFile: write
 * stdout there. DRY_RUN: printed + recorded, returning opts.sample (status mode: '0').
 */
String aws(List args, Map opts = [:]) {
    String line = Shell.join(['aws'] + args)
    if (isDryRun()) {
        echo "DRY-RUN: ${line}"
        record(line)
        return opts.sample != null ? opts.sample.toString() : (opts.status ? '0' : '')
    }
    record(line)
    if (opts.status) {
        return sh(label: opts.label ?: 'aws', returnStatus: true, script: "${line} >/dev/null").toString()
    }
    if (opts.toFile) {
        sh(label: opts.label ?: 'aws', script: "${line} > ${Shell.quote(opts.toFile)}")
        return ''
    }
    return sh(label: opts.label ?: 'aws', returnStdout: true, script: line).trim()
}

/** A shell pipeline involving aws (e.g. ECR login): printed in DRY_RUN, run otherwise. */
void awsShell(String script, String label = 'aws') {
    if (isDryRun()) {
        echo "DRY-RUN: ${script}"
        record(script)
        return
    }
    record(script.replaceAll(/--password-stdin.*/, '--password-stdin ...'))
    sh(label: label, script: script)
}

/**
 * Runs body with the AWS profile of an IAM role: grocery-ci (ECR push, CodeArtifact) or grocery-<env>-deployer
 * (ECS, SSM, SPA). The AWS CLI assumes the role itself (role_arn + credential_source), so no credential ever passes
 * through Jenkins. DRY_RUN: only printed.
 */
def withAws(String roleName, Closure body) {
    String arn = "arn:aws:iam::${account()}:role/${roleName}"
    if (isDryRun()) {
        echo "DRY-RUN: export AWS_PROFILE=${roleName}   # role_arn ${arn}, region ${region()}"
        record("export AWS_PROFILE=${roleName}   # assumes ${arn}")
        return body()
    }
    String source = env.GROCERY_AWS_CREDENTIAL_SOURCE ?: 'Ec2InstanceMetadata'
    String session = Shell.slug("jenkins-${env.BUILD_TAG}").take(64)
    writeFile file: '.ci/aws/config', text: """[profile ${roleName}]
role_arn = ${arn}
credential_source = ${source}
role_session_name = ${session}
region = ${region()}
"""
    return withEnv(["AWS_CONFIG_FILE=${env.WORKSPACE}/.ci/aws/config", "AWS_PROFILE=${roleName}",
                    "AWS_REGION=${region()}", "AWS_DEFAULT_REGION=${region()}", 'AWS_PAGER=']) {
        body()
    }
}

String deployerRole(String envName) { return "grocery-${envName}-deployer".toString() }

/** /grocery/<env>/shared/app-url (SSM), e.g. https://dev.grocery.example.com; DRY_RUN: from the env's tfvars. */
String appUrl(String envName) {
    String sample = "https://${envName == 'prod' ? '' : envName + '.'}grocery.example.com"
    String tfvars = "infrastructure/aws/terraform/envs/${envName}/${envName}.tfvars"
    if (isDryRun() && fileExists(tfvars)) {
        String domain = sh(returnStdout: true, script: "sed -n 's/^domain_name *= *\"\\([^\"]*\\)\".*/\\1/p' ${tfvars} | head -1").trim()
        if (domain) {
            sample = "https://${domain}"
        }
    }
    return aws(['ssm', 'get-parameter', '--name', "/grocery/${envName}/shared/app-url", '--query', 'Parameter.Value',
                '--output', 'text'], [sample: sample])
}

// ------------------------------------------------------------------------------------------------- notifications
/** Notification placeholder: always logged; e-mailed when the Jenkinsfile sets `notify` and Mailer has SMTP. */
void notify(Map cfg, String status, String detail = '') {
    String subject = "[grocery CI] ${env.JOB_NAME} #${env.BUILD_NUMBER} ${env.GROCERY_IMAGE_TAG ?: ''}: ${status}"
    String body = "${subject}\n${detail ? detail + '\n' : ''}${env.BUILD_URL ?: ''}\nmode: ${isDryRun() ? 'DRY_RUN' : 'LIVE'}"
    echo "NOTIFY ${cfg.notify ? '(e-mail to ' + cfg.notify + ')' : '(log only)'}: ${subject}${detail ? ' - ' + detail : ''}"
    if (cfg.notify) {
        try {
            mail(to: cfg.notify, subject: subject, body: body)
        } catch (err) {
            echo "NOTIFY: e-mail not sent (${err.message}); configure SMTP in Manage Jenkins > System > E-mail Notification"
        }
    }
}

/**
 * Archives .ci/reports and .ci/deploy of the current workspace (called at the end of every agent block). The deploy
 * calls of the block are kept as .ci/reports/aws-calls-<label>.txt (build = up to dev, staging, prod).
 */
void archiveReports(String label = 'build') {
    sh "if [ -f .ci/reports/aws-calls.txt ]; then mv .ci/reports/aws-calls.txt .ci/reports/aws-calls-${label}.txt; fi"
    archiveArtifacts artifacts: '.ci/reports/**,.ci/deploy/**,.ci/build-info.json', allowEmptyArchive: true, fingerprint: false
}

