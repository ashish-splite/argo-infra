def call(Map args = [:]) {

    String appName = args.name
    if (!appName) {
        error 'dockerGitOpsPipeline: "name" is required'
    }

    String dockerfile = args.dockerfile ?: 'Dockerfile'
    String buildContext = args.context ?: '.'
    String testCommand = args.testCommand

    podTemplate(containers: [
        containerTemplate(name: 'kaniko',
                          image: 'gcr.io/kaniko-project/executor:v1.23.2-debug',
                          command: 'sleep', args: '9999999', ttyEnabled: true),
        containerTemplate(name: 'yq',
                          image: 'mikefarah/yq:4',
                          command: 'sleep', args: '9999999', ttyEnabled: true)
    ]) {

        node(POD_LABEL) {

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
                env.GITOPS_IMAGE_REPO_KEY = app.gitops.imageRepoKey ?: '.image.repository'

                echo """
                    application : ${env.APP_NAME}
                    image repo  : ${env.IMAGE_REPO}
                    gitops repo : ${env.GITOPS_REPO}
                    values file : ${env.GITOPS_VALUES_FILE}
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

                container('kaniko') {
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

                                mkdir -p /kaniko/.docker
                                AUTH=$(printf '%s:%s' "$DOCKER_USER" "$DOCKER_PASS" \
                                       | base64 | tr -d '\\n')
                                cat > /kaniko/.docker/config.json <<CFG
                                {"auths":{"https://index.docker.io/v1/":{"auth":"$AUTH"}}}
CFG

                                /kaniko/executor \
                                    --context "dir://$WORKSPACE/$BUILD_CONTEXT" \
                                    --dockerfile "$WORKSPACE/$DOCKERFILE" \
                                    --destination "$IMAGE" \
                                    --cache=true \
                                    --snapshot-mode=redo
                            '''
                        }
                    }
                }
            }

            // The multibranch job has no parameters and always publishes; the manual job opts in.
            boolean updateGitOps = (params.UPDATE_GITOPS == null) ? true : params.UPDATE_GITOPS

            stage('Update GitOps') {

                if (!updateGitOps) {
                    echo "UPDATE_GITOPS is false - ${env.IMAGE} pushed, GitOps left untouched"
                    return
                }

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

                        GIT_ASKPASS="$PWD/.git-askpass" GIT_TERMINAL_PROMPT=0 \
                            git clone --branch "$GITOPS_BRANCH" "$GITOPS_REPO" gitops

                        test -f "gitops/$GITOPS_VALUES_FILE" \
                            || { echo "values file not found: $GITOPS_VALUES_FILE"; exit 1; }
                    '''

                    // strenv() sidesteps all shell/yq quoting of the values.
                    container('yq') {
                        withEnv(["NEW_REPO=${env.IMAGE_REPO}", "NEW_TAG=${env.GIT_SHA}"]) {
                            sh '''
                                set -eu
                                yq eval -i \
                                    "$GITOPS_IMAGE_REPO_KEY = strenv(NEW_REPO) | $GITOPS_IMAGE_TAG_KEY = strenv(NEW_TAG)" \
                                    "gitops/$GITOPS_VALUES_FILE"
                            '''
                        }
                    }

                    sh '''
                        set -eu

                        cd gitops
                        export GIT_ASKPASS="$WORKSPACE/.git-askpass"
                        export GIT_TERMINAL_PROMPT=0

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
                gitops      : ${updateGitOps ? env.GITOPS_VALUES_FILE : 'not updated'}
            """.stripIndent()
        }
    }
}
