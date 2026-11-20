// =====================================================================================================================
// groceryImage - build, push and promote images (contract Phase 14 §4).
//   tags: <semver>-<shortSha> (deployed) and sha-<fullSha>, both immutable in ECR; a promotion moves the mutable
//   tags dev / staging / prod to the same manifest without rebuilding.
//   repository = ECR repository name: grocery/<unit> (grocery/grocery-web, grocery/schema-init).
//   DRY_RUN: images go to the local registry standing in for ECR (GROCERY_CI_REGISTRY); the ECR calls are printed.
// =====================================================================================================================
import com.grocery.ci.Shell

/**
 * docker build of one image. a: unit, dockerfile, context, buildArgs (Map), platform, extraTags (List of tags).
 * Sets env.GROCERY_IMAGE (the <tag> reference scanned and pushed) and env.GROCERY_IMAGE_REPO.
 */
Map build(Map a) {
    String repo = "grocery/${a.unit}"
    String registry = groceryCi.pushRegistry() ?: 'grocery-ci.invalid'
    List<String> tags = [env.GROCERY_IMAGE_TAG, env.GROCERY_SHA_TAG] + (a.extraTags ?: [])
    List<String> refs = tags.unique().collect { "${registry}/${repo}:${it}".toString() }
    Map args = [VERSION : env.GROCERY_SEMVER, REVISION: env.GROCERY_REVISION, CREATED: env.GROCERY_CREATED,
                SOURCE  : env.GROCERY_SOURCE] + (a.buildArgs ?: [:])
    List cmd = ['docker', 'build', '--platform', a.platform ?: 'linux/amd64', '-f', a.dockerfile]
    args.each { k, v -> if (v != null && v != '') { cmd += ['--build-arg', "${k}=${v}"] } }
    refs.each { cmd += ['-t', it] }
    cmd << a.context
    sh(label: "docker build ${repo}", script: "DOCKER_BUILDKIT=1 ${Shell.join(cmd)}")
    env.GROCERY_IMAGE = refs[0]
    env.GROCERY_IMAGE_REPO = repo
    env.GROCERY_IMAGE_REFS = refs.join(' ')
    sh(label: 'image facts', script: "mkdir -p .ci/reports && docker image inspect --format " +
            "'{{json .Config.Labels}}' ${Shell.quote(refs[0])} | tee .ci/reports/image-labels.json && " +
            "docker image inspect --format 'id {{.Id}} size {{.Size}} user {{.Config.User}} arch {{.Architecture}}' ${Shell.quote(refs[0])}")
    return [repo: repo, refs: refs]
}

/** The jar of a Maven module -> .ci/image/app.jar, then Dockerfile.service. */
void buildService(Map cfg, String moduleDir) {
    String jar = sh(returnStdout: true, label: 'boot jar', script: "ls ${moduleDir}/target/*.jar | grep -v -e '-plain\\.jar\$' -e '-sources\\.jar\$' | head -1").trim()
    if (!jar) {
        error("no jar in ${moduleDir}/target (did the build stage run?)")
    }
    sh "rm -rf .ci/image && mkdir -p .ci/image && cp ${Shell.quote(jar)} .ci/image/app.jar && ls -l .ci/image"
    build(unit: cfg.service, dockerfile: 'infrastructure/ci/docker/Dockerfile.service', context: '.ci/image',
            platform: cfg.platform, buildArgs: [SERVICE: cfg.service, PORT: cfg.port.toString(), JRE_IMAGE: cfg.jreImage ?: null])
}

/** Pushes every tag of the image built in this run (ECR, or the local registry in DRY_RUN) and records the digest. */
void push(String unit) {
    List<String> refs = env.GROCERY_IMAGE_REFS.tokenize(' ')
    String repo = env.GROCERY_IMAGE_REPO
    String ecr = groceryCi.ecrRegistry()
    if (groceryCi.isDryRun()) {
        if (env.GROCERY_CI_REGISTRY) {
            for (String r in refs) {
                sh(label: "push ${r}", script: "docker push --quiet ${Shell.quote(r)}")
            }
            String digest = sh(returnStdout: true, script: "docker image inspect --format '{{index .RepoDigests 0}}' ${Shell.quote(refs[0])}").trim()
            env.GROCERY_IMAGE_DIGEST = digest.contains('@') ? digest.split('@')[1] : ''
            echo "pushed to the local registry (ECR stand-in): ${refs.join(', ')} digest ${env.GROCERY_IMAGE_DIGEST}"
            sh(label: 'registry check', script: "curl -sf http://${env.GROCERY_CI_REGISTRY_API ?: env.GROCERY_CI_REGISTRY}/v2/${repo}/tags/list || true")
        } else {
            echo 'DRY_RUN without GROCERY_CI_REGISTRY: nothing pushed'
        }
        groceryCi.withAws('grocery-ci') {
            groceryCi.awsShell("aws ecr get-login-password --region ${groceryCi.region()} | docker login --username AWS --password-stdin ${ecr}")
            for (String r in refs) {
                String target = "${ecr}/${repo}:${r.tokenize(':').last()}"
                groceryCi.awsShell(Shell.join(['docker', 'tag', r, target]) + ' && ' + Shell.join(['docker', 'push', target]))
            }
        }
        return
    }
    withEnv(["DOCKER_CONFIG=${env.WORKSPACE}/.ci/docker"]) {
        groceryCi.withAws('grocery-ci') {
            groceryCi.awsShell("aws ecr get-login-password --region ${groceryCi.region()} | docker login --username AWS --password-stdin ${ecr}", 'ECR login')
            for (String r in refs) {
                String tag = r.tokenize(':').last()
                // immutable tags: a re-run of the same revision finds its tags already there
                String existing = groceryCi.aws(['ecr', 'describe-images', '--repository-name', repo, '--image-ids', "imageTag=${tag}",
                                                 '--query', 'imageDetails[0].imageDigest', '--output', 'text'], [status: true])
                if (existing == '0') {
                    echo "${repo}:${tag} already in ECR (immutable): not pushed again"
                } else {
                    sh(label: "push ${r}", script: "docker push --quiet ${Shell.quote(r)}")
                }
            }
        }
    }
    String digest = sh(returnStdout: true, script: "docker image inspect --format '{{index .RepoDigests 0}}' ${Shell.quote(refs[0])} || true").trim()
    env.GROCERY_IMAGE_DIGEST = digest.contains('@') ? digest.split('@')[1] : ''
}

/**
 * Promotion: the mutable tag <envName> points at the deployed image (no rebuild). ECR: batch-get-image + put-image
 * with the same manifest (grocery-ci role). DRY_RUN: re-tagged in the local registry, ECR calls printed.
 */
void promote(String unit, String envName) {
    String repo = "grocery/${unit}"
    String tag = env.GROCERY_IMAGE_TAG
    if (groceryCi.isDryRun() && env.GROCERY_CI_REGISTRY) {
        String src = "${env.GROCERY_CI_REGISTRY}/${repo}:${tag}"
        String dst = "${env.GROCERY_CI_REGISTRY}/${repo}:${envName}"
        sh(label: "promote ${envName}", script: "docker pull --quiet ${Shell.quote(src)} >/dev/null 2>&1 || true; " +
                "docker tag ${Shell.quote(src)} ${Shell.quote(dst)} && docker push --quiet ${Shell.quote(dst)}")
    }
    sh 'mkdir -p .ci/deploy'
    String manifest = ".ci/deploy/manifest-${envName}.json"
    groceryCi.withAws('grocery-ci') {
        groceryCi.aws(['ecr', 'batch-get-image', '--repository-name', repo, '--image-ids', "imageTag=${tag}",
                       '--query', 'images[0].imageManifest', '--output', 'text'], [toFile: manifest, sample: '{}'])
        def status = groceryCi.aws(['ecr', 'put-image', '--repository-name', repo, '--image-tag', envName,
                                    '--image-manifest', "file://${manifest}"], [status: true, sample: '0'])
        if (status != '0') {
            echo "put-image ${repo}:${envName} returned ${status} (ImageAlreadyExists when ${envName} already points at this manifest)"
        }
    }
}
