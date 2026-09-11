def call(Map args = [:]) {

    String appName = args.name
    if (!appName) {
        error 'dockerGitOpsPipeline: "name" is required'
    }

    String dockerfile = args.dockerfile ?: 'Dockerfile'
    String buildContext = args.context ?: '.'
    String testCommand = args.testCommand

    node {
        // brew-launched Jenkins runs with a minimal PATH that excludes docker and git.
        withEnv(['PATH+LOCAL=/usr/local/bin:/opt/homebrew/bin']) {

            stage('Resolve Configuration') {

                def registry = readYaml(text: libraryResource('applications.yaml'))

                def app = registry.applications.find { it.name == appName }
                if (!app) {
                    error "'${appName}' is not registered in applications.yaml"
                }

                env.APP_NAME = appName
                env.IMAGE_REPO = app.image.repository
                env.GITOPS_REPO = app.gitops.repository
                env.GITOPS_BRANCH = app.gitops.branch ?: 'main'
                env.GITOPS_VALUES_FILE = app.gitops.valuesFile
                env.GITOPS_IMAGE_TAG_KEY = app.gitops.imageTagKey ?: '.image.tag'

                echo """
                    application : ${env.APP_NAME}
                    image repo  : ${env.IMAGE_REPO}
                    gitops repo : ${env.GITOPS_REPO}
                    values file : ${env.GITOPS_VALUES_FILE}
                    tag key     : ${env.GITOPS_IMAGE_TAG_KEY}
                """.stripIndent()
            }

            stage('Checkout Source') {

                deleteDir()
                checkout scm

                env.GIT_SHA = sh(
                    script: 'git rev-parse --short HEAD',
                    returnStdout: true
                ).trim()

                env.IMAGE = "${env.IMAGE_REPO}:${env.GIT_SHA}"

                currentBuild.displayName = "${appName} @ ${env.GIT_SHA}"

                echo "Image: ${env.IMAGE}"
            }

            if (testCommand) {
                stage('Test') {
                    sh testCommand
                }
            }

            stage('Build & Push Image') {

                withCredentials([
                    usernamePassword(
                        credentialsId: 'dockerhub-credentials',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )
                ]) {
                    withEnv(["DOCKERFILE=${dockerfile}", "BUILD_CONTEXT=${buildContext}"]) {
                        sh '''
                            set -eu

                            echo "$DOCKER_PASS" | docker login \
                                --username "$DOCKER_USER" \
                                --password-stdin

                            trap 'docker logout >/dev/null 2>&1 || true' EXIT

                            docker build \
                                --file "$DOCKERFILE" \
                                --tag "$IMAGE" \
                                "$BUILD_CONTEXT"

                            docker push "$IMAGE"
                        '''
                    }
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
                        set -eu

                        rm -rf gitops

                        # Keeps the token out of .git/config, argv and the build log.
                        cat > .git-askpass <<'ASKPASS'
#!/bin/sh
case "$1" in
  Username*) printf '%s' "$GIT_USER" ;;
  Password*) printf '%s' "$GIT_TOKEN" ;;
esac
ASKPASS
                        chmod 700 .git-askpass
                        export GIT_ASKPASS="$PWD/.git-askpass"
                        export GIT_TERMINAL_PROMPT=0

                        git clone --branch "$GITOPS_BRANCH" "$GITOPS_REPO" gitops
                        cd gitops

                        if [ ! -f "$GITOPS_VALUES_FILE" ]; then
                            echo "values file not found: $GITOPS_VALUES_FILE"
                            exit 1
                        fi

                        # strenv() sidesteps all shell/yq quoting of the tag value.
                        docker run --rm \
                            -e NEW_TAG="$GIT_SHA" \
                            -v "$WORKSPACE/gitops:/workdir" \
                            mikefarah/yq \
                            eval -i \
                            "$GITOPS_IMAGE_TAG_KEY = strenv(NEW_TAG)" \
                            "/workdir/$GITOPS_VALUES_FILE"

                        git config user.email "jenkins-ci@local"
                        git config user.name "jenkins-ci"
                        git add "$GITOPS_VALUES_FILE"

                        if git diff --cached --quiet; then
                            echo "already at $GIT_SHA - nothing to push"
                            exit 0
                        fi

                        git commit -m "chore($APP_NAME): bump image tag to $GIT_SHA"

                        # Concurrent app builds all target the same branch.
                        n=1
                        until git push origin "HEAD:$GITOPS_BRANCH"; do
                            n=$((n + 1))
                            if [ "$n" -gt 3 ]; then
                                echo "push failed after 3 attempts"
                                exit 1
                            fi
                            echo "push rejected - rebasing and retrying ($n/3)"
                            git pull --rebase origin "$GITOPS_BRANCH"
                        done
                    '''
                }
            }

            echo """
                CI complete.

                application : ${env.APP_NAME}
                image       : ${env.IMAGE}
                values file : ${env.GITOPS_VALUES_FILE}

                Argo CD will pick up the GitOps commit and sync.
            """.stripIndent()
        }
    }
}
