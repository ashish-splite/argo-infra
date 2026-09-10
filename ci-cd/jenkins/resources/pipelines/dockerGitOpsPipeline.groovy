def call(Map config) {

    // -------------------------------------------------------------------------
    // Validate configuration
    // -------------------------------------------------------------------------

    def appName = config.name
    def sourceRepo = config.source.repository
    def sourceBranch = config.source.branch ?: 'main'

    def imageRepository = config.image.repository

    def gitOpsRepo = config.gitops.repository
    def gitOpsBranch = config.gitops.branch ?: 'main'
    def valuesFile = config.gitops.valuesFile
    def imageTagKey = config.gitops.imageTagKey ?: '.image.tag'

    if (!appName) {
        error "Application name is required"
    }

    if (!sourceRepo) {
        error "source.repository is required for ${appName}"
    }

    if (!imageRepository) {
        error "image.repository is required for ${appName}"
    }

    if (!gitOpsRepo) {
        error "gitops.repository is required for ${appName}"
    }

    if (!valuesFile) {
        error "gitops.valuesFile is required for ${appName}"
    }

    // -------------------------------------------------------------------------
    // Pipeline
    // -------------------------------------------------------------------------

    pipeline {

        agent any

        environment {
            APP_NAME = appName

            SOURCE_REPO = sourceRepo
            SOURCE_BRANCH = sourceBranch

            IMAGE_REPOSITORY = imageRepository

            GITOPS_REPO = gitOpsRepo
            GITOPS_BRANCH = gitOpsBranch
            GITOPS_VALUES_FILE = valuesFile
            GITOPS_IMAGE_TAG_KEY = imageTagKey
        }

        stages {

            // -----------------------------------------------------------------
            // Checkout application source
            // -----------------------------------------------------------------

            stage('Checkout') {
                steps {
                    echo "Building ${APP_NAME}"
                    echo "Source: ${SOURCE_REPO}"
                    echo "Branch: ${SOURCE_BRANCH}"

                    git(
                        url: SOURCE_REPO,
                        branch: SOURCE_BRANCH,
                        credentialsId: 'github-credentials'
                    )

                    script {
                        env.GIT_SHA = sh(
                            script: 'git rev-parse HEAD',
                            returnStdout: true
                        ).trim()

                        env.IMAGE = "${IMAGE_REPOSITORY}:${GIT_SHA}"

                        echo "Commit: ${GIT_SHA}"
                        echo "Image:  ${IMAGE}"
                    }
                }
            }

            // -----------------------------------------------------------------
            // Build and push Docker image
            // -----------------------------------------------------------------

            stage('Build & Push Image') {
                steps {
                    withCredentials([
                        usernamePassword(
                            credentialsId: 'dockerhub-credentials',
                            usernameVariable: 'DOCKER_USER',
                            passwordVariable: 'DOCKER_PASS'
                        )
                    ]) {
                        sh '''
                            set -e

                            echo "$DOCKER_PASS" | docker login \
                                --username "$DOCKER_USER" \
                                --password-stdin

                            docker build \
                                --tag "$IMAGE" \
                                .

                            docker push "$IMAGE"

                            docker logout
                        '''
                    }
                }
            }

            // -----------------------------------------------------------------
            // Update GitOps repository
            // -----------------------------------------------------------------

            stage('Update GitOps') {
                steps {
                    withCredentials([
                        usernamePassword(
                            credentialsId: 'github-credentials',
                            usernameVariable: 'GIT_USER',
                            passwordVariable: 'GIT_TOKEN'
                        )
                    ]) {

                        sh '''
                            set -e

                            rm -rf gitops

                            AUTH_URL=$(echo "$GITOPS_REPO" | \
                                sed "s#https://#https://$GIT_USER:$GIT_TOKEN@#")

                            git clone \
                                --branch "$GITOPS_BRANCH" \
                                "$AUTH_URL" \
                                gitops

                            cd gitops

                            if [ ! -f "$GITOPS_VALUES_FILE" ]; then
                                echo "ERROR: Values file not found:"
                                echo "$GITOPS_VALUES_FILE"
                                exit 1
                            fi

                            echo "Updating:"
                            echo "  File: $GITOPS_VALUES_FILE"
                            echo "  Key:  $GITOPS_IMAGE_TAG_KEY"
                            echo "  Tag:  $GIT_SHA"

                            docker run --rm \
                                -v "$WORKSPACE/gitops:/workdir" \
                                mikefarah/yq \
                                eval -i \
                                "${GITOPS_IMAGE_TAG_KEY} = \\"${GIT_SHA}\\"" \
                                "/workdir/${GITOPS_VALUES_FILE}"

                            git config user.email "jenkins-ci@thedarkest22.local"
                            git config user.name "jenkins-ci"

                            git add "$GITOPS_VALUES_FILE"

                            if git diff --cached --quiet; then
                                echo "No GitOps changes required"
                            else
                                git commit \
                                    -m "chore(${APP_NAME}): bump image to ${GIT_SHA}"

                                git push \
                                    "$AUTH_URL" \
                                    HEAD:"$GITOPS_BRANCH"
                            fi
                        '''
                    }
                }
            }
        }

        post {
            success {
                echo """
                CI completed successfully.

                Application: ${APP_NAME}
                Image:       ${IMAGE}
                GitOps repo: ${GITOPS_REPO}
                Values file: ${GITOPS_VALUES_FILE}

                Argo CD will detect the GitOps change and sync the new image.
                """
            }

            failure {
                echo "Pipeline failed for ${APP_NAME}"
            }
        }
    }
}