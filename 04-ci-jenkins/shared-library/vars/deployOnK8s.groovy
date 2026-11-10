def call(String appDir, String image, String tag, String kubeCredId) {
    dir(appDir) {
        sh "sed -i 's|image: ${image}:.*|image: ${image}:${tag}|' deployment.yaml"
        withCredentials([file(credentialsId: kubeCredId, variable: 'KUBECONFIG')]) {
            sh 'kubectl apply -f deployment.yaml'
            sh 'kubectl rollout status deployment/ivolveapp -n ivolve --timeout=180s'
        }
    }
}
