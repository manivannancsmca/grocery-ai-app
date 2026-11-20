// =====================================================================================================================
// groceryDeploy - deployments (contract Phase 14 §5), all through the grocery-<env>-deployer role:
//   ecs()             describe-task-definition -> swap the image of container "app" -> register-task-definition ->
//                     update-service (native ECS blue/green: test traffic, bake time, alarm + circuit-breaker
//                     rollback) -> wait services-stable -> check the new revision really runs -> image tag to SSM
//   registerSchemas() the schema-init one-off task of the grocery-event-schemas version the service pins,
//                     before the producer deploys (BACKWARD_TRANSITIVE, never registers an incompatible schema)
//   spa()             infrastructure/ci/scripts/deploy-spa.sh (S3 releases/<sha>/ + CloudFront origin path)
//   rollbackOnFailure() infrastructure/ci/scripts/rollback.sh for the environment being deployed when the run failed
// Names: cluster grocery-<env>-cluster, service = task family = grocery-<env>-<unit>, SSM /grocery/<env>/... (the
// Terraform deploy contract, /grocery/<env>/deploy/contract). Migrations are never rolled back (expand/contract).
// =====================================================================================================================
import com.grocery.ci.Shell

String sampleArn(String family, int revision) {
    return "arn:aws:ecs:${groceryCi.region()}:${groceryCi.account()}:task-definition/${family}:${revision}".toString()
}

/** The previous revision is remembered for a rollback of this environment if anything fails before verified(). */
void activate(String envName, String previousTaskDefinition) {
    env.GROCERY_ACTIVE_ENV = envName
    env.GROCERY_PREVIOUS_TASKDEF = previousTaskDefinition ?: ''
}

/** The environment passed its post-deployment tests: a later failure no longer rolls it back. */
void verified(String envName) {
    if (env.GROCERY_ACTIVE_ENV == envName) {
        env.GROCERY_ACTIVE_ENV = ''
    }
    env.GROCERY_VERIFIED_ENVS = ((env.GROCERY_VERIFIED_ENVS ?: '').tokenize(',') + [envName]).unique().join(',')
}

/** Waits for a stable service; the CLI waiter gives up after 10 min, a blue/green with bake time can take longer. */
void waitStable(String cluster, String service, int minutes) {
    List args = ['ecs', 'wait', 'services-stable', '--cluster', cluster, '--services', service]
    if (groceryCi.isDryRun()) {
        groceryCi.aws(args)
        return
    }
    timeout(time: minutes, unit: 'MINUTES') {
        waitUntil(initialRecurrencePeriod: 15000, quiet: true) {
            return groceryCi.aws(args, [status: true, label: "wait for ${service}"]) == '0'
        }
    }
}

/** Renders .ci/deploy/<env>/taskdef-<unit>.json (live from describe-task-definition, or the DRY_RUN template). */
String renderTaskDefinition(String envName, String unit, String image) {
    String family = "grocery-${envName}-${unit}"
    String dir = ".ci/deploy/${envName}"
    sh "mkdir -p ${dir}"
    String current = "${dir}/current-${unit}.json"
    String out = "${dir}/taskdef-${unit}.json"
    groceryCi.aws(['ecs', 'describe-task-definition', '--task-definition', family, '--include', 'TAGS', '--output', 'json'],
            [toFile: current, label: "describe ${family}"])
    List cmd = ['python3', 'infrastructure/ci/scripts/render-task-definition.py', '--unit', unit, '--env', envName,
                '--image', image, '--account', groceryCi.account(), '--region', groceryCi.region(), '--out', out]
    if (!groceryCi.isDryRun()) {
        cmd += ['--current', current]
    }
    sh(label: "render ${family}", script: Shell.join(cmd))
    if (groceryCi.isDryRun()) {
        echo "DRY-RUN: rendered ${out} (template: services.auto.tfvars.json + envs/${envName}/${envName}.tfvars):\n" +
                readFile(out)
    }
    return out
}

/** Deploys <ecr>/grocery/<unit>:<tag> to grocery-<env>-<unit>. Returns [previous: arn, registered: arn]. */
Map ecs(String envName, String unit, String tag, Map opts = [:]) {
    String family = "grocery-${envName}-${unit}"
    String cluster = "grocery-${envName}-cluster"
    String image = "${groceryCi.ecrRegistry()}/grocery/${unit}:${tag}"
    int sampleRevision = 42
    Map result = [:]
    groceryCi.withAws(groceryCi.deployerRole(envName)) {
        String previous = groceryCi.aws(['ecs', 'describe-services', '--cluster', cluster, '--services', family,
                                         '--query', 'services[0].taskDefinition', '--output', 'text'],
                [sample: sampleArn(family, sampleRevision)])
        if (!previous || previous == 'None') {
            error("ECS service ${family} not found in ${cluster} - apply infrastructure/aws/terraform/envs/${envName} first")
        }
        String taskdef = renderTaskDefinition(envName, unit, image)
        String registered = groceryCi.aws(['ecs', 'register-task-definition', '--cli-input-json', "file://${taskdef}",
                                           '--query', 'taskDefinition.taskDefinitionArn', '--output', 'text'],
                [sample: sampleArn(family, sampleRevision + 1)])
        activate(envName, previous)
        groceryCi.aws(['ecs', 'update-service', '--cluster', cluster, '--service', family, '--task-definition', registered,
                       '--query', 'service.taskDefinition', '--output', 'text'], [sample: registered])
        waitStable(cluster, family, (opts.waitMinutes ?: 45) as int)
        String running = groceryCi.aws(['ecs', 'describe-services', '--cluster', cluster, '--services', family,
                                        '--query', 'services[0].taskDefinition', '--output', 'text'], [sample: registered])
        if (running != registered) {
            error("${family} runs ${running}, not ${registered}: ECS rolled the deployment back (alarm or circuit breaker); " +
                    "see the service's deployment events and /grocery/${envName}/${unit} in CloudWatch Logs")
        }
        groceryCi.aws(['ssm', 'put-parameter', '--name', "/grocery/${envName}/${unit}/image-tag", '--value', tag,
                       '--type', 'String', '--overwrite', '--query', 'Version', '--output', 'text'], [sample: '7'])
        result = [previous: previous, registered: registered]
    }
    writeJSON file: ".ci/deploy/${envName}/result-${unit}.json", json: result + [image: image, tag: tag], pretty: 2
    echo "deployed ${image} to ${family}: ${result.previous} -> ${result.registered}"
    return result
}

/**
 * Registers the schemas of grocery-event-schemas <version> in <env>: the schema-init one-off task with the image
 * grocery/schema-init:<version> (built and pushed by the grocery-event-schemas library pipeline).
 */
void registerSchemas(String envName, String version) {
    if (!version) {
        error('producesEvents is set but the pom pins no com.grocery:grocery-event-schemas version')
    }
    String unit = 'schema-init'
    String repo = "grocery/${unit}"
    String family = "grocery-${envName}-${unit}"
    String cluster = "grocery-${envName}-cluster"
    String image = "${groceryCi.ecrRegistry()}/${repo}:${version}"
    groceryCi.withAws('grocery-ci') {
        String found = groceryCi.aws(['ecr', 'describe-images', '--repository-name', repo, '--image-ids', "imageTag=${version}",
                                      '--query', 'imageDetails[0].imageDigest', '--output', 'text'], [status: true])
        if (found != '0') {
            error("${repo}:${version} is not in ECR - run the grocery-event-schemas library pipeline for ${version} first")
        }
    }
    groceryCi.withAws(groceryCi.deployerRole(envName)) {
        String taskdef = renderTaskDefinition(envName, unit, image)
        String registered = groceryCi.aws(['ecs', 'register-task-definition', '--cli-input-json', "file://${taskdef}",
                                           '--query', 'taskDefinition.taskDefinitionArn', '--output', 'text'],
                [sample: sampleArn(family, 8)])
        String contract = groceryCi.aws(['ssm', 'get-parameter', '--name', "/grocery/${envName}/deploy/contract",
                                         '--query', 'Parameter.Value', '--output', 'text'],
                [sample: '{"network":{"subnets":["subnet-0sample0000000001","subnet-0sample0000000002"],"security_groups":["sg-0sample000000000001"]}}'])
        def network = readJSON(text: contract).network
        String subnets = network.subnets.collect { it.toString() }.join(',')
        String groups = network.security_groups.collect { it.toString() }.join(',')
        String netcfg = "awsvpcConfiguration={subnets=[${subnets}],securityGroups=[${groups}],assignPublicIp=DISABLED}"
        String task = groceryCi.aws(['ecs', 'run-task', '--cluster', cluster, '--task-definition', registered,
                                     '--launch-type', 'FARGATE', '--count', '1', '--network-configuration', netcfg,
                                     '--started-by', "jenkins-${env.BUILD_NUMBER}", '--query', 'tasks[0].taskArn', '--output', 'text'],
                [sample: "arn:aws:ecs:${groceryCi.region()}:${groceryCi.account()}:task/${cluster}/0123456789abcdef0123456789abcdef"])
        groceryCi.aws(['ecs', 'wait', 'tasks-stopped', '--cluster', cluster, '--tasks', task])
        String exitCode = groceryCi.aws(['ecs', 'describe-tasks', '--cluster', cluster, '--tasks', task, '--query',
                                         'tasks[0].containers[?name==`app`].exitCode | [0]', '--output', 'text'], [sample: '0'])
        if (exitCode != '0') {
            error("schema-init ${version} failed in ${envName} (exit ${exitCode}): see CloudWatch Logs /grocery/${envName}/schema-init")
        }
        groceryCi.aws(['ssm', 'put-parameter', '--name', "/grocery/${envName}/${unit}/image-tag", '--value', version,
                       '--type', 'String', '--overwrite', '--query', 'Version', '--output', 'text'], [sample: '3'])
    }
    echo "schemas of grocery-event-schemas ${version} registered in ${envName}"
}

/** The web app: S3 releases/<revision>/ + CloudFront origin path (deploy-spa.sh). */
void spa(String envName, String distDir) {
    String log = ".ci/deploy/${envName}/deploy-spa.log"
    sh "mkdir -p .ci/deploy/${envName}"
    activate(envName, '')
    env.GROCERY_SPA_SWITCHED = ''
    int status = 0
    groceryCi.withAws(groceryCi.deployerRole(envName)) {
        status = sh(label: "deploy-spa ${envName}", returnStatus: true, script: """#!/bin/bash
set -o pipefail
DRY_RUN=${groceryCi.isDryRun() ? 1 : 0} BUILD_TAG=${Shell.quote(env.BUILD_TAG)} \\
  bash infrastructure/ci/scripts/deploy-spa.sh ${envName} ${env.GROCERY_REVISION} ${Shell.quote(distDir)} 2>&1 | tee ${log}
""")
    }
    String output = readFile(log)
    // a rollback only makes sense once CloudFront was pointed at the new release
    if (output.contains('origin path:')) {
        env.GROCERY_SPA_SWITCHED = envName
    }
    sh "grep '^DRY-RUN: ' ${log} | sed 's/^DRY-RUN: //' >> .ci/reports/aws-calls.txt || true"
    if (status != 0) {
        error("deploy-spa.sh ${envName} failed (exit ${status})")
    }
}

/**
 * post { failure }: rolls back the environment whose deployment or post-deployment tests failed (never the ones
 * already verified), with the revision that ran before this deployment.
 */
void rollbackOnFailure(Map cfg, String unit) {
    String envName = env.GROCERY_ACTIVE_ENV
    if (!envName) {
        echo 'rollback: no deployment in progress when the run failed - nothing to roll back'
        return
    }
    List cmd
    Map extraEnv = [:]
    if (unit == 'frontend') {
        if (env.GROCERY_SPA_SWITCHED != envName) {
            echo "rollback: CloudFront of ${envName} was not switched to this release - nothing to roll back"
            return
        }
        cmd = ['bash', 'infrastructure/ci/scripts/rollback.sh', envName, 'frontend']
        extraEnv.DRY_RUN_CURRENT_RELEASE = env.GROCERY_REVISION
    } else {
        String previous = env.GROCERY_PREVIOUS_TASKDEF ?: ''
        String revision = previous.tokenize(':') ? previous.tokenize(':').last() : ''
        if (!(revision ==~ /[0-9]+/)) {
            echo "rollback: previous task definition of ${envName} unknown ('${previous}') - rolling back to the newest older revision"
            revision = ''
        }
        cmd = ['bash', 'infrastructure/ci/scripts/rollback.sh', envName, unit] + (revision ? [revision] : [])
        if (revision) {
            extraEnv.DRY_RUN_CURRENT_REVISION = ((revision as int) + 1).toString()
        }
    }
    node("${cfg.agentLabel}") {
        groceryCi.unstashTools()
        String envPrefix = (['DRY_RUN': groceryCi.isDryRun() ? '1' : '0'] + extraEnv).collect { k, v -> "${k}=${Shell.quote(v)}" }.join(' ')
        groceryCi.withAws(groceryCi.deployerRole(envName)) {
            int rc = sh(label: "rollback ${envName}", returnStatus: true,
                    script: """#!/bin/bash
set -o pipefail
${envPrefix} ${Shell.join(cmd)} 2>&1 | tee .ci/reports/rollback-${envName}.log
""")
            sh "grep '^DRY-RUN: ' .ci/reports/rollback-${envName}.log | sed 's/^DRY-RUN: //' >> .ci/reports/aws-calls.txt || true"
            groceryCi.notify(cfg, rc == 0 ? 'FAILED - ROLLED BACK' : 'FAILED - ROLLBACK FAILED',
                    "${unit} in ${envName}: ${Shell.join(cmd)} exited ${rc}")
        }
        sh 'mv .ci/reports/aws-calls.txt .ci/reports/aws-calls-rollback.txt || true'
        archiveArtifacts artifacts: '.ci/reports/rollback-*.log,.ci/reports/aws-calls-rollback.txt', allowEmptyArchive: true
    }
}
