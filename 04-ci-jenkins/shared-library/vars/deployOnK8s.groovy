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
