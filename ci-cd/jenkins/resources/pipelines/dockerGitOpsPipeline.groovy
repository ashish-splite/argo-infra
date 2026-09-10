def call(Map config) {

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

    stage('Checkout App') {

        echo "Building ${appName}"
        echo "Source: ${sourceRepo}"
        echo "Branch: ${sourceBranch}"

        deleteDir()

        git(
            url: sourceRepo,
            branch: sourceBranch,
            credentialsId: 'github-credentials'
        )

        env.GIT_SHA = sh(
            script: 'git rev-parse HEAD',
            returnStdout: true
        ).trim()

        env.IMAGE = "${imageRepository}:${env.GIT_SHA}"

        echo "Commit: ${env.GIT_SHA}"
        echo "Image:  ${env.IMAGE}"
    }

    stage('Build & Push Image') {

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

    stage('Update GitOps') {

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

    echo """
    CI completed successfully.

    Application: ${appName}
    Image:       ${env.IMAGE}
    GitOps repo: ${gitOpsRepo}
    Values file: ${valuesFile}

    Argo CD will detect the GitOps change and sync the new image.
    """
}

return this