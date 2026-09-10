folder('applications') {
    description('Application CI pipelines')
}

applications.each { app ->

    def appName = app.name

    pipelineJob("applications/${appName}") {

        description("CI pipeline for ${appName}")

        definition {
            cps {
                script("""
                    def appName = '${appName}'

                    node {

                        stage('Checkout CI Configuration') {

                            deleteDir()

                            git(
                                url: 'https://github.com/ashish-splite/argo-infra.git',
                                branch: 'main',
                                credentialsId: 'github-credentials'
                            )
                        }

                        stage('Load Application Configuration') {

                            def config = readYaml(
                                file: 'ci-cd/jenkins/resources/applications.yaml'
                            )

                            def app = config.applications.find {
                                it.name == appName
                            }

                            if (!app) {
                                error "Application '\${appName}' not found in applications.yaml"
                            }

                            echo "Loaded configuration for \${appName}"

                            def pipeline = load(
                                'ci-cd/jenkins/resources/pipelines/dockerGitOpsPipeline.groovy'
                            )

                            pipeline.call(app)
                        }
                    }
                """)

                sandbox()
            }
        }
    }
}