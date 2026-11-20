// =====================================================================================================================
// groceryTests - tests against a deployed environment (the Phase 13 suites, contract Phase 13 §4):
//   api()  tests/api-tests (REST Assured), JUnit tag expression, e.g. smoke after dev/prod
//   e2e()  tests/e2e (Playwright in its image) against the environment's app URL
//   zap()  tests/security/zap-baseline.sh (OWASP ZAP baseline) against the app URL
// The environment's URL is /grocery/<env>/shared/app-url (SSM). The admin account of the api suite comes from the
// Jenkins credential grocery-<env>-admin (username/password: the bootstrap SUPER_ADMIN of that environment).
// DRY_RUN: there is no environment to test - the exact commands are printed and recorded instead.
// The e2e and ZAP scripts bind-mount their folder, so they need an agent whose workspace path is visible to its
// Docker daemon (a normal Linux agent; not a controller-in-Docker like the local validation Jenkins).
// =====================================================================================================================
import com.grocery.ci.Shell

boolean printOnly(String what, String command) {
    if (groceryCi.isDryRun()) {
        echo "DRY-RUN: ${what}: ${command}"
        groceryCi.record("${command}   # ${what}")
        return true
    }
    return false
}

void api(String envName, String groups) {
    String url = groceryCi.appUrl(envName)
    String command = "mvn -B -f tests/api-tests/pom.xml verify -Dgroups=${Shell.quote(groups)} -DGATEWAY_URL=${url}" +
            ' -DADMIN_EMAIL=$ADMIN_EMAIL -DADMIN_PASSWORD=$ADMIN_PASSWORD'
    if (printOnly("API tests (${groups}) against ${envName}, admin from credential grocery-${envName}-admin", command)) {
        return
    }
    withCredentials([usernamePassword(credentialsId: "grocery-${envName}-admin", usernameVariable: 'ADMIN_EMAIL',
            passwordVariable: 'ADMIN_PASSWORD')]) {
        try {
            groceryCi.maven(name: "api-tests-${envName}", memory: '2g', passEnv: ['ADMIN_EMAIL', 'ADMIN_PASSWORD'],
                    script: command)
        } finally {
            junit allowEmptyResults: true, testResults: 'tests/api-tests/target/surefire-reports/*.xml'
        }
    }
}

void e2e(String envName, String args = '') {
    String url = groceryCi.appUrl(envName)
    String command = "APP_URL=${url} API_URL=${url} FRONTEND_UPSTREAM= E2E_NETWORK=bridge E2E_RETRIES=1 CI=true bash tests/e2e/run-e2e.sh ${args}".trim()
    if (printOnly("Playwright journeys against ${envName}", command)) {
        return
    }
    try {
        sh(label: "e2e ${envName}", script: command)
    } finally {
        junit allowEmptyResults: true, testResults: 'tests/e2e/test-results/junit.xml'
        archiveArtifacts artifacts: 'tests/e2e/playwright-report/**,tests/e2e/test-results/results.json', allowEmptyArchive: true
    }
}

void zap(String envName) {
    String url = groceryCi.appUrl(envName)
    String command = "ZAP_NETWORK=bridge FRONTEND_URL=${url} GATEWAY_URL=${url} bash tests/security/zap-baseline.sh"
    if (printOnly("OWASP ZAP baseline against ${envName}", command)) {
        return
    }
    try {
        sh(label: "zap ${envName}", script: command)
    } finally {
        archiveArtifacts artifacts: 'tests/security/reports/zap-baseline-*', allowEmptyArchive: true
    }
}

/** After a SPA deployment: the app shell and the API answer through CloudFront. */
void appResponds(String envName) {
    String url = groceryCi.appUrl(envName)
    String command = "curl -fsS --retry 5 --retry-all-errors ${url}/ | grep -q '<div id=\"root\">' && curl -fsS --retry 5 --retry-all-errors ${url}/api/health/identity"
    if (printOnly("app shell and API through CloudFront (${envName})", command)) {
        return
    }
    sh(label: "check ${envName}", script: command)
}
