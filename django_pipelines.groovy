#!groovy

def artifact_list = new URL("https://gist.githubusercontent.com/joepreludian/7029ad9238b858a59930bea33e65d623/raw/053b588260822f88381c3f7c4cab885e5d40982f/example.json").openConnection()

def slurper = new groovy.json.JsonSlurper()
def list_available_names = []

def getRC = artifact_list.getResponseCode()
println(getRC)

if(getRC.equals(200)) {
    json_stuff = slurper.parseText(get.getInputStream().getText())

    json_stuff.each { item ->
        list_available_names.push(item['name'])
    }
}


properties([
  parameters([
    string(name: 'ARTIFACT_NAME', choices: list_available_names, description: 'List got from an app', )
   ])
])

def buildProject(args) {
  pipeline_version = '1.0.0'

  project_version = null
  project_zip = null
  environment_variables = args.get('project_environment_variables', [])
  docker_image_name_with_version = null
  docker_extra_params = null

  withEnv(environment_variables) {
    node {
      cleanWs()
      checkout scm

      // Fetching the project version
      project_version = sh returnStdout: true, script: 'cat VERSION || echo "latest"'
      project_version = project_version.trim()

      // Composition of the project Artifact
      project_zip = "${args.project_name}-${project_version}-b${env.BUILD_NUMBER}.zip"
      docker_image_name_with_version = "${args.project_name}:${project_version}"

      echo "Preludian - Django Pipelines - v. ${pipeline_version}"

      echo "Project ZIP name: ${project_zip}"

      figlet "Django Pipelines - args"
      
      echo "- docker_extra_options: ${args.docker_extra_options}"
      echo "- python_django_wsgi: ${args.python_django_wsgi}"
      echo "- python_django_main_module: ${args.python_django_main_module}"
      echo "- python_version: ${args.python_version}"
      echo "- project_environment_variables: ${environment_variables}"
      echo "-- env.DJANGO_PIPELINES_JENKINS_HOME_VOL: ${env.DJANGO_PIPELINES_JENKINS_HOME_VOL}"
      echo "-- Django Project version: ${project_version}"
      echo "-- Docker Image Name: ${docker_image_name_with_version}"

      def docker_config_jenkins_home_vol = args.docker_config_jenkins_home_vol ? args.docker_config_jenkins_home_vol : env.PRELUDIAN_PIPELINES_JENKINS_HOME_VOL
      if (!docker_config_jenkins_home_vol)
        error "Jenkins configuration not found - please set docker_config_jenkins_home_vol or DJANGO_PIPELINES_JENKINS_HOME_VOL"

        
      docker.image("mysql:${args.mysql_sidecar.version}").withRun("-e \"MYSQL_ROOT_PASSWORD=${args.mysql_sidecar.root_password}\" -e \"MYSQL_DATABASE=${args.mysql_sidecar.database_name}\"") { db_container ->
        
        sh 'echo "Waiting Mysql Being ready..." && sleep 4'
        
        def docker_extra_options = args.docker_extra_options ? args.docker_extra_options : "-v ${docker_config_jenkins_home_vol}:/var/jenkins_home -u root:root"
        docker_extra_params = "${docker_extra_options} --link ${db_container.id}:${args.mysql_sidecar.host_name}"
        echo "-- docker_extra_params: ${docker_extra_params}"

        docker.image("python:${args.python_version}").inside(docker_extra_params) {
            stage('Install dependencies') {
                figlet "${args.project_name} - ${project_version}"
                figlet 'DJ - Install Dependencies'

                echo "PROJECT NAME: ${args.python_django_wsgi} - MAIN MODULE: ${args.python_django_main_module}"
                sh 'pip install --upgrade pipenv && pipenv install --system --deploy'
            }

            stage('Run migrations') {
                figlet 'Django - Run migrations'
                sh 'python manage.py migrate'
            }

            stage('Collect Static') {
                figlet 'Django - Collect Static'

                sh 'python manage.py collectstatic --noinput'
                stash includes: 'static/', name: 'django_static'
            }
        }
      }
      
      if (args.node_yarn_install_static) {
        stage('Node - yarn install on static') {
          docker.image("node").inside(args.docker_extra_options) {
              unstash 'django_static'

              sh 'npm install -g yarn'
              sh 'cd static; yarn'

              stash includes: 'static/', name: 'django_static_yarn'
          }
        }
      }
      
      stage('Prepare Artifact') {
        figlet 'DJ - Prepare Artifact'

        cleanWs()
        checkout scm
       
        if (args.node_yarn_install_static) {
          unstash 'django_static_yarn'
        } else {
          unstash 'django_static'
        }
        
        zip zipFile: 'project.zip', dir: '.'
        stash includes: 'project.zip', name: 'django_project'
      }
      
      if (args.project_docker_inject) {
        stage('Install Docker Data') {
          figlet 'DJ - Install Docker'
          
          cleanWs()
          unstash 'django_project'
          
          sh 'wget https://github.com/joepreludian/django-jenkins-pipelines/archive/master.zip -O pipeline_temp.zip'
          sh 'unzip pipeline_temp.zip -d .'

          sh "mkdir dist; cd dist; unzip ../project.zip -d .; cp -Rv ../django-jenkins-pipelines-master/django_jenkins_pipeline/* ."
          
          sh "cd dist; cat supervisord.conf"
          sh "cd dist; sed -i \'s/%python_version%/${args.python_version}/g\' Dockerfile"
          sh "cd dist; sed -i \'s/%project_name%/${args.python_django_wsgi}/g\' supervisord.conf"

          zip zipFile: project_zip, dir: 'dist'
          archiveArtifacts artifacts: project_zip, fingerprint: true
          stash includes: project_zip, name: 'django_project_final'
        }
      }

      if (args.project_docker_create_image) {
        stage('Build Docker Image') {
          figlet 'DJ - Build Docker Image'
          cleanWs()

          unstash 'django_project_final'
          sh "mkdir dist && unzip ${project_zip} -d dist"

          sh "docker build -t ${docker_image_name_with_version} dist/"
        }
      }
      
      if (args.post_exec){
        stage('Post Exec') {
          cleanWs()

          figlet 'DJ - Post Exec'
          
          unstash 'django_project_final'

          docker_image = args.project_docker_create_image ? docker_image_name_with_version : null

          echo '- post_exec found: executing function injecting variables'
          echo "- project_zip: ${project_zip}"
          echo "- docker_image: ${docker_image}"
          
          sh 'ls -lh'

          args.post_exec(project_zip, docker_image)
        }
      }
    }
  }
}

return this
