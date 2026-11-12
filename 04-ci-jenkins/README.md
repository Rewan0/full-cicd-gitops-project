# Stage 4: Continuous Integration with Jenkins

A CI pipeline for the Node.js application in which the pipeline logic lives in reusable Jenkins Shared Libraries and the build runs on a dedicated Jenkins agent connected over SSH. The pipeline builds the application, builds and scans the Docker image, pushes it to Docker Hub, and records the new image tag in the Kubernetes manifest stored in Git. Deployment to the cluster is handled by ArgoCD in Stage 5, so Jenkins never talks to the Kubernetes API.

## Architecture

```
  GitHub: iVolve-tasks                 GitHub: jenkins-shared-lib
  (app source + Jenkinsfile            (vars/*.groovy)
   + manifest)                                 |
            |                                  |  @Library('shared-lib')
            |  Pipeline from SCM               |
            v                                  v
  +--------------------------------------------------------+
  |        Jenkins Controller (Docker Desktop container)   |
  +----------------------------+---------------------------+
                               |
                               |  SSH (port 22)
                               v
  +--------------------------------------------------------+
  |   Jenkins Agent: linux-agent   (label: agent-1)        |
  |   master-1 VM: Java 21, Node.js 20, Docker, Trivy      |
  +------------+----------------------------+--------------+
               |                            |
               | 1. docker push             | 2. git push
               |    image :lab23-N          |    updated image tag
               v                            v
         +-----------+              +-------------------+
         | Docker Hub|              | GitHub (manifest) |---> ArgoCD (Stage 5)
         +-----------+              +-------------------+
```

Pipeline flow:

```
Checkout --> Build App --> Build Image --> Scan --> Push --> Delete Local Image --> Update Manifest --> Git Push
             (buildApp)    |---------------- buildImage ----------------|          |---- deployOnK8s ----|
```

## Repository Contents

| Path | Description |
|---|---|
| `Jenkinsfile` | Declarative pipeline that loads the shared library and runs on the `agent-1` node |
| `shared-library/vars/buildApp.groovy` | Install dependencies and run tests |
| `shared-library/vars/buildImage.groovy` | Build, scan, push, and remove the image |
| `shared-library/vars/deployOnK8s.groovy` | Update the image tag in the manifest and push it to GitHub |
| `README.md` | Procedure (this file) |

The shared library is maintained in its own repository, [jenkins-shared-lib](https://github.com/Rewan0/jenkins-shared-lib), because Jenkins requires `vars/` at the root of a library repository. The copy in `shared-library/` is a reference mirror of that repository.

The application and the Jenkinsfile were run from the `iVolve-tasks` repository: the application source in `Jenkins/Lab22/` and the Jenkinsfile and watched manifest in `ArgoCD/Lab23/`. Screenshots are stored in `../docs/images/04-jenkins/`.

## Tools and Technologies

| Tool | Purpose |
|---|---|
| Jenkins | CI server (the controller runs in a Docker Desktop container) |
| Jenkins Shared Library | Reusable pipeline steps |
| Jenkins SSH Agent | Runs the pipeline on a separate machine |
| Node.js and npm | Application build and tests |
| Docker | Build, push, and remove the image |
| Trivy | Image vulnerability scanning |
| Docker Hub | Image registry (`rewan2233/ivolve-app`) |
| Git and GitHub | Source control for the application, the manifest, and the library |

## Part 1: Jenkins Agent

### Agent prerequisites

The agent is the `master-1` VM from Stage 2.

| Requirement | Check |
|---|---|
| Java 21 (required by the current Jenkins remoting) | `java -version` |
| Node.js and npm | `node -v && npm -v` |
| Docker, with the user in the `docker` group | `docker ps` |
| Trivy | `trivy --version` |
| Git | `git --version` |
| SSH server | `systemctl status ssh` |

```bash
# Add the user to the docker group
sudo usermod -aG docker $USER

# Create the agent root directory
sudo mkdir /home/jenkins_home
sudo chmod 777 /home/jenkins_home
```

### SSH key (inside the Jenkins container)

```bash
docker exec -it jenkins bash
ssh-keygen -t rsa -b 2048 -f ~/.ssh/jenkins_agent_key
ssh-copy-id -i ~/.ssh/jenkins_agent_key.pub <USER>@<AGENT_IP>
ssh -i ~/.ssh/jenkins_agent_key <USER>@<AGENT_IP>
```

The last command must connect without a password prompt.

### SSH credential

`Manage Jenkins` -> `Credentials` -> `Global` -> `Add Credentials`

| Field | Value |
|---|---|
| Kind | SSH Username with private key |
| ID | `agent-ssh` |
| Username | The agent VM user |
| Private Key | Enter directly, paste the private key |

<img width="1276" height="682" alt="Screenshot 2026-11-05 172832" src="https://github.com/user-attachments/assets/f9bbbfb5-5ecb-4273-85ab-cc07d0bf461d" />
### Agent node

`Manage Jenkins` -> `Nodes` -> `New Node`

| Setting | Value |
|---|---|
| Name | `linux-agent` |
| Type | Permanent Agent |
| Number of executors | `1` |
| Remote root directory | `/home/jenkins_home` |
| Labels | `agent-1` |
| Usage | Use this node as much as possible |
| Launch method | Launch agents via SSH |
| Host | `<AGENT_IP>` |
| Credentials | `agent-ssh` |
| Host Key Verification | Non verifying Verification Strategy |

The node log shows `Agent successfully connected and online`.

<img width="1265" height="686" alt="Screenshot 2026-11-05 173148" src="https://github.com/user-attachments/assets/925d56de-9697-4c59-9d99-7d09b1210008" />
<img width="1274" height="683" alt="Screenshot 2026-11-05 174311" src="https://github.com/user-attachments/assets/4da3714b-751d-4d93-9222-b0f49f5b0dfb" />

## Part 2: Shared Libraries

Each file in `vars/` defines one function named `call`. The file name is the function name used in the Jenkinsfile.

| Library | Responsibility |
|---|---|
| `buildApp` | Install dependencies and run tests |
| `buildImage` | Build the image, scan it with Trivy, push to Docker Hub, remove the local image |
| `deployOnK8s` | Update the image tag in the manifest and push it to GitHub so ArgoCD synchronizes the cluster |

`vars/buildApp.groovy`

```groovy
def call(String appDir) {
    dir(appDir) {
        sh 'npm install'
        sh 'npm test --if-present'
    }
}
```

`vars/buildImage.groovy`

```groovy
def call(String appDir, String image, String tag, String dockerCredId) {
    dir(appDir) {
        sh "docker build -t ${image}:${tag} ."
    }
    sh "trivy image --severity HIGH,CRITICAL ${image}:${tag}"
    withCredentials([usernamePassword(credentialsId: dockerCredId,
        usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
        sh 'echo $DH_PASS | docker login -u $DH_USER --password-stdin'
        sh "docker push ${image}:${tag}"
    }
    sh "docker rmi ${image}:${tag}"
}
```

`vars/deployOnK8s.groovy`

```groovy
def call(String manifestPath, String image, String tag, String credsId, String branch = 'main') {
    withCredentials([usernamePassword(credentialsId: credsId,
                     usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASS')]) {
        sh """
            sed -i 's|image: ${image}:.*|image: ${image}:${tag}|' ${manifestPath}
            git config user.email "jenkins@ivolve.local"
            git config user.name  "jenkins"
            git add ${manifestPath}
            git commit -m "Update image to ${image}:${tag} [skip ci]" || echo "No changes to commit"
            git push https://\${GIT_USER}:\${GIT_PASS}@github.com/Rewan0/iVolve-tasks.git HEAD:${branch}
        """
    }
}
```

Key points of `deployOnK8s`:

- It originally ran `kubectl apply` against the cluster. It now only edits the manifest and pushes the change, which moves deployment to ArgoCD (Stage 5).
- The `sed` expression targets only the application image, so the `mysql:8.0` image used by the init container is left unchanged.
- The commit message contains `[skip ci]` to prevent the push from triggering another build.
- The GitHub token is read from a Jenkins credential and escaped in the shell script, so it is never interpolated into the Groovy string.

<img width="2532" height="1285" alt="image" src="https://github.com/user-attachments/assets/f24bbd32-85b1-47a1-9877-8c466fe88ffe" />

## Part 3: Jenkins Configuration

### Register the shared library

`Manage Jenkins` -> `System` -> `Global Trusted Pipeline Libraries` -> `Add`

| Field | Value |
|---|---|
| Name | `shared-lib` |
| Default version | `main` |
| Retrieval method | Modern SCM |
| Source Code Management | Git |
| Project Repository | URL of the `jenkins-shared-lib` repository |
| Credentials | None (public repository) |

<img width="1279" height="686" alt="Screenshot (7)" src="https://github.com/user-attachments/assets/8608f46d-f814-4e66-86a9-3c8dbffd480b" />

### Credentials used by the pipeline

| Credential ID | Type | Used for |
|---|---|---|
| `dockerhub` | Username with password | Docker Hub login and push |
| `github-creds` | Username with password | GitHub username and personal access token (`repo` scope) for pushing the manifest |
| `agent-ssh` | SSH key | Controller to agent connection |

Credentials are used only inside `withCredentials` blocks, so no secret is stored in any repository, and Jenkins masks their values in the console output. Jenkins needs no kubeconfig, because it no longer deploys to the cluster.

### Pipeline job

`New Item` -> `Lab23` -> `Pipeline`

| Setting | Value |
|---|---|
| Definition | Pipeline script from SCM |
| SCM | Git |
| Repository URL | `https://github.com/Rewan0/iVolve-tasks.git` |
| Branch | `*/main` |
| Script Path | `ArgoCD/Lab23/Jenkinsfile` |

## Part 4: Jenkinsfile

```groovy
@Library('shared-lib') _

pipeline {
    agent { label 'agent-1' }

    environment {
        IMAGE    = "rewan2233/ivolve-app"
        TAG      = "lab23-${BUILD_NUMBER}"
        APP_DIR  = "Jenkins/Lab22"
        MANIFEST = "ArgoCD/Lab23/deployment.yaml"
    }

    stages {
        stage('Build App') {
            steps { buildApp(APP_DIR) }
        }
        stage('Build Image') {
            steps { buildImage(APP_DIR, IMAGE, TAG, 'dockerhub') }
        }
        stage('Update Manifest & Push to GitHub') {
            steps { deployOnK8s(MANIFEST, IMAGE, TAG, 'github-creds') }
        }
    }

    post {
        always  { echo 'Pipeline finished' }
        success { echo 'Manifest pushed, Argo CD will sync' }
        failure { echo 'Pipeline failed' }
    }
}
```

| Line | Meaning |
|---|---|
| `@Library('shared-lib') _` | Loads the shared library registered in Jenkins |
| `agent { label 'agent-1' }` | Runs the whole pipeline on the SSH agent |
| `TAG = lab23-BUILD_NUMBER` | Every build produces a unique, traceable image version instead of `latest` |
| `MANIFEST` | The manifest that ArgoCD watches; Jenkins edits only the image tag in it |

## Run and Verify

1. Open the `Lab23` job and click Build Now.
2. In the console output, confirm `Running on linux-agent`, that the `shared-lib` library is loaded, and that the build ends with `Finished: SUCCESS`.
3. Confirm on Docker Hub that the new tag exists, and on GitHub that Jenkins pushed a commit named `Update image to rewan2233/ivolve-app:lab23-N [skip ci]`.

<img width="2548" height="1360" alt="image" src="https://github.com/user-attachments/assets/cb97993b-e3ad-48fa-9a75-4edf256e266a" />
<img width="2413" height="718" alt="image" src="https://github.com/user-attachments/assets/24487b01-da34-4eef-8602-4c53d1b2fd00" />
<img width="2545" height="1368" alt="image" src="https://github.com/user-attachments/assets/18d3b4dd-0b88-4de0-9194-6287a3f4910d" />
<img width="1722" height="252" alt="image" src="https://github.com/user-attachments/assets/94b6ec5d-d899-4829-8d71-e71db5e0b4bd" />

## Notes

- The Trivy scan reports HIGH and CRITICAL findings but does not fail the build. Add `--exit-code 1` to the `trivy` command to enforce a quality gate.
- Updating a function in `jenkins-shared-lib` affects every pipeline that uses it, with no change to the Jenkinsfile.
- Running the pipeline on the agent keeps build load off the Jenkins controller.

## Next Stage

Continue to [Stage 5: CD with ArgoCD](../05-cd-argocd/README.md), where ArgoCD detects the new commit and synchronizes the cluster.
