import jenkins.model.*
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

START_TIMESTAMP = new Date().getTime()
HAS_ERROR = false

GIT_URL='https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git'
GIT_HASH='producer'

RUNNING_NODES=0

JAR_FILES=""
CLASSPATH_VALUES=""

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
                sh '''
                    echo "Building comsumer"
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
                    CLASSPATH_VALUES=$(cat $JAR_FILES)
                    java -classpath target/aws-kinesisvideo-producer-sdk-canary-consumer-1.0-SNAPSHOT.jar:${CLASSPATH_VALUES} -Daws.accessKeyId=$AWS_ACCESS_KEY_ID -Daws.secretKey=$AWS_SECRET_ACCESS_KEY com.amazon.kinesis.video.canary.consumer.ProducerSdkCanaryConsumer
                '''
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
        'CANARY_STREAM_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}",
        'CANARY_TYPE': params.CANARY_TYPE,
        'FRAGMENT_SIZE_IN_BYTES' : params.FRAGMENT_SIZE_IN_BYTES,
        'CANARY_DURATION_IN_SECONDS': params.CANARY_DURATION_IN_SECONDS,
        'AWS_DEFAULT_REGION': params.AWS_DEFAULT_REGION,
    ].collect({k,v -> "${k}=${v}" })

    def credentials = [
        [
            $class: 'AmazonWebServicesCredentialsBinding', 
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            credentialsId: 'SDK_CANARY_CREDS',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        ]
    ]
    
    def consumerStartUpDelay = 30
    echo "NODE_NAME = ${env.NODE_NAME}"
    checkout([
        scm: [
            $class: 'GitSCM', 
            branches: [[name: GIT_HASH]],
            userRemoteConfigs: [[url: GIT_URL]]
        ]
    ])
    RUNNING_NODES++
    echo "Number of running nodes: ${RUNNING_NODES}"
    if(isProducer) {
        buildProducer()
    }
    else {
        // This is to make sure that the consumer does not make RUNNING_NODES
        // zero before producer build starts. Should handle this in a better
        // way
        sleep consumerStartUpDelay    
    }
    RUNNING_NODES--
    echo "Number of running nodes after build: ${RUNNING_NODES}"
    waitUntil {
        RUNNING_NODES == 0
    }
    
    echo "Done waiting in NODE_NAME = ${env.NODE_NAME}"
    if(!isProducer) {
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
        string(name: 'CANARY_TYPE')
        string(name: 'FRAGMENT_SIZE_IN_BYTES')
        string(name: 'RUNNER_LABEL')
        string(name: 'CANARY_DURATION_IN_SECONDS')
        string(name: 'MIN_RETRY_DELAY_IN_SECONDS')
        string(name: 'AWS_DEFAULT_REGION')
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
                        script {
                            runClient(true, params)
                            sh """
                                cd $WORKSPACE
                                sudo rm -r *
                            """
                        }
                    }
                }
                stage('Consumer') {
                    agent {
                        label params.CONSUMER_NODE_LABEL
                    }
                    steps {
                        script {
                            runClient(false, params)
                            sh """
                                cd $WORKSPACE
                                sudo rm -r *
                            """
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
                                string(name: 'PRODUCER_NODE_LABEL', value: params.PRODUCER_NODE_LABEL),
                                string(name: 'CONSUMER_NODE_LABEL', value: params.CONSUMER_NODE_LABEL),
                                string(name: 'GIT_URL', value: params.GIT_URL),
                                string(name: 'GIT_HASH', value: params.GIT_HASH),
                                string(name: 'CANARY_TYPE', value: params.CANARY_TYPE),
                                string(name: 'FRAGMENT_SIZE_IN_BYTES', value: params.FRAGMENT_SIZE_IN_BYTES),
                                string(name: 'CANARY_DURATION_IN_SECONDS', value: params.CANARY_DURATION_IN_SECONDS),
                                string(name: 'MIN_RETRY_DELAY_IN_SECONDS', value: params.MIN_RETRY_DELAY_IN_SECONDS),
                                string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                                string(name: 'RUNNER_LABEL', value: params.RUNNER_LABEL),
                            ],
                    wait: false
                )
            }
        }
    }
}