// =====================================================================================================================
// grocerySecurity - the Trivy gates (contract Phase 14 §4): dependencies + secrets + IaC config of the sources, the
// built image (+ CycloneDX SBOM), an unpacked artifact. Fails on HIGH/CRITICAL findings that have a fix; accepted
// findings: tests/security/.trivyignore + infrastructure/ci/docker/.trivyignore + <module>/.trivyignore.
// Trivy runs in aquasec/trivy (pinned) with its DB cached in the volume GROCERY_CI_TRIVY_VOLUME.
// =====================================================================================================================
import com.grocery.ci.Config
import com.grocery.ci.Shell

/** Merges the accepted-findings files into .ci/trivyignore. */
void prepareIgnoreFile(String moduleDir) {
    List files = ['tests/security/.trivyignore', 'infrastructure/ci/docker/.trivyignore']
    if (moduleDir) {
        files << "${moduleDir}/.trivyignore".toString()
    }
    sh(label: 'accepted findings', script: "mkdir -p .ci && for f in ${files.collect { Shell.quote(it) }.join(' ')}; do " +
            'if [ -f "$f" ]; then echo "# ---- $f"; cat "$f"; echo; fi; done > .ci/trivyignore; ' +
            'echo "accepted findings: $(grep -cE \'^[A-Za-z]+-[0-9A-Za-z-]+\' .ci/trivyignore || true) ids"')
}

void trivy(String label, List args, boolean dockerSocket) {
    groceryCi.ensureVolumes([groceryCi.trivyVolume()])
    writeFile file: '.ci/trivy-gate.sh', text: libraryResource('com/grocery/ci/trivy-gate.sh')
    groceryCi.inContainer(image: Config.TRIVY_IMAGE, name: "trivy-${label}", memory: '2g', dockerSocket: dockerSocket,
            env: [TRIVY_CACHE_DIR: '/trivy-cache', TRIVY_NO_PROGRESS: 'true', TRIVY_DISABLE_VEX_NOTICE: 'true',
                  HOME: '/tmp/trivy-home'],
            // Maven poms are resolved from the CI repository (read-only); no download from Maven Central
            volumes: ["${groceryCi.trivyVolume()}:/trivy-cache", "${groceryCi.m2Volume()}:/tmp/trivy-home/.m2:ro"],
            script: "sh .ci/trivy-gate.sh ${args.collect { Shell.quote(it) }.join(' ')}")
}

/** Dependency + secret scan of dependencyTarget, IaC config scan of each configTarget. */
void scanSources(String label, String moduleDir, String dependencyTarget, List configTargets) {
    prepareIgnoreFile(moduleDir)
    try {
        trivy(label, ['sources', label, dependencyTarget] + configTargets, false)
    } finally {
        archiveArtifacts artifacts: '.ci/reports/trivy-*', allowEmptyArchive: true
    }
}

/** Vulnerability + secret scan of a local image and its CycloneDX SBOM (.ci/reports/sbom-<label>-<tag>.cdx.json). */
void scanImage(String label, String image, String moduleDir) {
    prepareIgnoreFile(moduleDir)
    String sbom = ".ci/reports/sbom-${label}-${env.GROCERY_IMAGE_TAG}.cdx.json"
    try {
        trivy(label, ['image', label, image, sbom], true)
    } finally {
        archiveArtifacts artifacts: '.ci/reports/trivy-*,.ci/reports/sbom-*', allowEmptyArchive: true, fingerprint: true
    }
}

/** Scan of an unpacked artifact directory (jars) and its SBOM. */
void scanDirectory(String label, String dir) {
    prepareIgnoreFile(null)
    String sbom = ".ci/reports/sbom-${label}.cdx.json"
    try {
        trivy(label, ['rootfs', label, dir, sbom], false)
    } finally {
        archiveArtifacts artifacts: '.ci/reports/trivy-*,.ci/reports/sbom-*', allowEmptyArchive: true, fingerprint: true
    }
}
