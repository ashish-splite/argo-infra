folder('applications') {
    description('Application CI pipelines')
}

applications.each { app ->

    def appName = app.name

    pipelineJob("applications/${appName}") {

        description("""
            CI pipeline for ${appName}.

            Source:
            ${app.source.repository}

            Image:
            ${app.image.repository}

            GitOps values:
            ${app.gitops.valuesFile}
        """.stripIndent())

        definition {
            cps {
                script("""
                    pipeline {
                        agent any

                        stages {

                            stage('Checkout') {
                                steps {
                                    echo 'Building ${appName}'
                                }
                            }

                            stage('Test Configuration') {
                                steps {
                                    echo 'Source repository: ${app.source.repository}'
                                    echo 'Source branch: ${app.source.branch}'
                                    echo 'Image repository: ${app.image.repository}'
                                    echo 'GitOps repository: ${app.gitops.repository}'
                                    echo 'Values file: ${app.gitops.valuesFile}'
                                }
                            }
                        }
                    }
                """)

                sandbox()
            }
        }
    }
}