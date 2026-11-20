// =====================================================================================================================
// Job DSL of the local validation Jenkins: one pipeline job per Jenkinsfile of the mounted repository (the repository
// is not a git repository, so a job holds a copy of its Jenkinsfile and the pipeline copies /repo into its
// workspace). Run by JCasC at start-up and by the "seed-jobs" job (after adding or editing a Jenkinsfile).
//   services/<svc>/Jenkinsfile                          -> services/<svc>
//   libs/<lib>/Jenkinsfile                              -> libs/<lib>
//   frontend/<app>/Jenkinsfile                          -> frontend/<app>
//   infrastructure/ci/jenkins/pipelines/<x>/Jenkinsfile -> platform/<x>
// =====================================================================================================================
def repo = new File(System.getenv('GROCERY_LOCAL_REPO') ?: '/repo')
def roots = [
        services: 'services',
        libs    : 'libs',
        frontend: 'frontend',
        platform: 'infrastructure/ci/jenkins/pipelines',
]

def jobsFound = []
roots.each { folderName, path ->
    def dir = new File(repo, path)
    def modules = (dir.listFiles() ?: []).findAll { it.isDirectory() && new File(it, 'Jenkinsfile').isFile() }
    modules.sort { it.name }.each { module ->
        jobsFound << [folder: folderName, name: module.name, file: new File(module, 'Jenkinsfile'), path: "${path}/${module.name}/Jenkinsfile"]
    }
}

roots.keySet().each { folderName ->
    folder(folderName) {
        description("grocery ${folderName} pipelines (local validation Jenkins)")
    }
}

jobsFound.each { j ->
    pipelineJob("${j.folder}/${j.name}") {
        description("${j.path} of the mounted repository (copied when the job was seeded - run seed-jobs after editing it). " +
                'Local Jenkins: every AWS call is a DRY_RUN; images go to the local registry.')
        logRotator {
            numToKeep(15)
            artifactNumToKeep(5)
        }
        // the pipeline declares the same parameters; defined here too so the very first build accepts them
        parameters {
            booleanParam('DRY_RUN', true, 'Print every AWS call instead of making it (always on in the local Jenkins).')
            booleanParam('VALIDATE_ONLY', false, 'Only load the Jenkinsfile and the shared library and validate the configuration.')
        }
        definition {
            cps {
                script(j.file.getText('UTF-8'))
                sandbox(true)
            }
        }
    }
}

pipelineJob('seed-jobs') {
    description('Re-reads every Jenkinsfile of the mounted repository and (re)creates the jobs (infrastructure/ci/jenkins/local/jobs/jobs.groovy).')
    definition {
        cps {
            script("""node {
    jobDsl(scriptText: readFile('${repo.path}/infrastructure/ci/jenkins/local/jobs/jobs.groovy'),
           sandbox: false, removedJobAction: 'DELETE', removedViewAction: 'IGNORE')
}""")
            sandbox(true)
        }
    }
}

println "jobs.groovy: ${jobsFound.size()} pipeline jobs: ${jobsFound.collect { "${it.folder}/${it.name}" }.join(', ')}"
