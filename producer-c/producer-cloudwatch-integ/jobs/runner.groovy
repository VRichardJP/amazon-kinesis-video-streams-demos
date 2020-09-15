import jenkins.model.*
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

START_TIMESTAMP = new Date().getTime()
RUNNING_NODES_IN_BUILDING = 0
HAS_ERROR = false

GIT_URL='https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git'
GIT_HASH='producer'

JAR_FILES=""
CLASSPATH_VALUES=""

producerBuilt=false
def buildProducer() {
  sh  """
    cd $WORKSPACE/producer-c/producer-cloudwatch-integ && 
    if [ ! -d "build" ] 
    then
        mkdir build
    fi
    cd build && 
    sudo cmake .. && 
    sudo make 
  """
  producerBuilt=true
}

def buildConsumer() {
  sh '''
    JAVA_HOME='/opt/jdk-13.0.1'
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
    M2_HOME='/opt/apache-maven-3.6.3'
    PATH="$M2_HOME/bin:$PATH"
    export PATH
    cd $WORKSPACE/consumer-java/aws-kinesis-video-producer-sdk-canary-consumer
    mvn package
    # Create a temporary filename in /tmp directory
    JAR_FILES=$(mktemp)
    # Create classpath string of dependencies from the local repository to a file
    mvn -Dmdep.outputFile=$JAR_FILES dependency:build-classpath
    CLASSPATH_VALUES=$(cat ${JAR_FILES})
  '''
}

def runProducer(envs) {
    def credentials = [
        [
            $class: 'AmazonWebServicesCredentialsBinding', 
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            credentialsId: 'SDK_CANARY_CREDS',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        ]
    ]
    RUNNING_NODES_IN_BUILDING++
    withEnv(envs) {
        withCredentials(credentials) {
            try {
                sh """
                    cd $WORKSPACE/producer-c/producer-cloudwatch-integ/build && 
                    ./kvsProducerSampleCloudwatch
                """
            } catch (FlowInterruptedException err) {
                echo 'Aborted producer due to cancellation'
                throw err
            } catch (err) {
                HAS_ERROR = true
                // Ignore errors so that we can auto recover by retrying
                unstable err.toString()
            }
        }
    }
}

def runConsumer(envs) {
    def credentials = [
        [
            $class: 'AmazonWebServicesCredentialsBinding', 
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            credentialsId: 'SDK_CANARY_CREDS',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        ]
    ]

    withEnv(envs) {
        withCredentials(credentials) {
             try {
                sh """
                    java -classpath target/aws-kinesisvideo-producer-sdk-canary-consumer-1.0-SNAPSHOT.jar:$CLASSPATH_VALUES -Daws.accessKeyId=$AWS_ACCESS_KEY_ID -Daws.secretKey=$AWS_SECRET_ACCESS_KEY com.amazon.kinesis.video.canary.consumer.ProducerSdkCanaryConsumer "${envs.CANARY_STREAM_NAME}" "${envs.CANARY_TYPE}" "${envs.FRAGMENT_SIZE_IN_BYTES}" "us-west-2"
                """
            } catch (FlowInterruptedException err) {
                echo 'Aborted consumer due to cancellation'
                throw err
            } catch (err) {
                HAS_ERROR = true
                // Ignore errors so that we can auto recover by retrying
                unstable err.toString()
            }
        }
    }
}   

def runClient(isProducer, params) {
    def envs = [
        'AWS_KVS_LOG_LEVEL': params.AWS_KVS_LOG_LEVEL,
        'CANARY_STREAM_NAME': "${env.JOB_NAME}",
        'CANARY_TYPE': params.CANARY_TYPE,
        'FRAGMENT_SIZE_IN_BYTES' : params.FRAGMENT_SIZE_IN_BYTES,
        'CANARY_DURATION_IN_SECONDS': params.CANARY_DURATION_IN_SECONDS
    ].collect({k,v -> "${k}=${v}" })

    def credentials = [
        [
            $class: 'AmazonWebServicesCredentialsBinding', 
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            credentialsId: 'SDK_CANARY_CREDS',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        ]
    ]
    
    RUNNING_NODES_IN_BUILDING++
    
    def consumerStartUpDelay = 10
    

    if(!isProducer) {
        waitUntil {
            producerBuilt == true
        }
        producerBuilt = false
        sleep consumerStartUpDelay
        // Run consumer
        runConsumer(envs)
    }
    else {
        runProducer(envs)
    }
}

pipeline {
    agent {
        label params.PRODUCER_NODE_LABEL
    }

    parameters {
        choice(name: 'AWS_KVS_LOG_LEVEL', choices: ["1", "2", "3", "4", "5"])
        string(name: 'PRODUCER_NODE_LABEL')
        string(name: 'CONSUMER_NODE_LABEL')
        string(name: 'GIT_URL')
        string(name: 'GIT_HASH')
        string(name: 'CANARY_STREAM_NAME')
        string(name: 'CANARY_TYPE')
        string(name: 'FRAGMENT_SIZE_IN_BYTES')
        string(name: 'CANARY_DURATION_IN_SECONDS')
        string(name: 'MIN_RETRY_DELAY_IN_SECONDS')
    }

    stages {
        stage('Echo params') {
            steps {
                echo params.toString()
            }
        }
        stage('Run') {
            failFast true
            parallel {
                stage('Producer') {
                    steps {
                        script{
                            checkout([
                                scm: [
                                    $class: 'GitSCM', 
                                    branches: [[name: GIT_HASH]],
                                    userRemoteConfigs: [[url: GIT_URL]]]
                            ])
                            echo "NODE_NAME = ${env.NODE_NAME}"
                            echo 'Build Producer'
                            buildProducer()
                            PRODUCER_NODE=env.NODE_NAME
                        }
                        script {
                            runClient(true, params)
                        }
                    }
                }
                stage('Consumer') {
                    agent {
                        label params.CONSUMER_NODE_LABEL
                    }
                    steps {
                        script{
                            echo env.NODE_NAME
                            checkout([
                                scm: [
                                    $class: 'GitSCM', 
                                    branches: [[name: GIT_HASH]],
                                    userRemoteConfigs: [[url: GIT_URL]]]
                            ])
                          echo "NODE_NAME = ${env.NODE_NAME}"
                          echo 'Build Consumer'
                          buildConsumer()
                          CONSUMER_NODE=env.NODE_NAME
                        }
                        script {
                            runClient(false, params)
                        }
                    }                
                }
            }
        }

        // In case of failures, we should add some delays so that we don't get into a tight loop of retrying
        stage('Throttling Retry') {
            when {
                equals expected: true, actual: HAS_ERROR
            }

            steps {
                sleep Math.max(0, params.MIN_RETRY_DELAY_IN_SECONDS.toInteger() - currentBuild.duration.intdiv(1000))
            }
        }

        stage('Reschedule') {
            steps {
                // TODO: Maybe there's a better way to write this instead of duplicating it
                build(
                    job: env.JOB_NAME,
                    parameters: [
                            parameters: [
                                string(name: 'AWS_KVS_LOG_LEVEL', value: "2"),
                                string(name: 'GIT_URL', value: GIT_URL),
                                string(name: 'GIT_HASH', value: GIT_HASH),
                                string(name: 'CANARY_DURATION_IN_SECONDS', value: CANARY_DURATION_IN_SECONDS.toString()),
                                string(name: 'MIN_RETRY_DELAY_IN_SECONDS', value: params.MIN_RETRY_DELAY_IN_SECONDS),
                                string(name: 'FRAGMENT_SIZE_IN_BYTES', value: params.FRAGMENT_SIZE_IN_BYTES.toString()),
                                string(name: 'PRODUCER_NODE_LABEL', value: "producer-uw2"),
                                string(name: 'CONSUMER_NODE_LABEL', value: "consumer-uw2"),
                                string(name: 'RUNNER_LABEL', value: "Periodic"),
                            ],
                    ],
                    wait: false
                )
            }
        }
    }
}