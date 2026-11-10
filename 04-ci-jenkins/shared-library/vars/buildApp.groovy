def call(String appDir) {
    dir(appDir) {
        sh 'npm install'
        sh 'npm test --if-present'
    }
}
