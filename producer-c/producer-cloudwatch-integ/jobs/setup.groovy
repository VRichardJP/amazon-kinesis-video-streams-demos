import jenkins.model.*

WORKSPACE_PRODUCER="producer-c/producer-cloudwatch-integ"
WORKSPACE_CONSUMER="consumer-java/aws-kinesis-video-producer-sdk-canary-consumer"
GIT_URL='https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git'
GIT_HASH='producer'
RUNNER_JOB_NAME_PREFIX = "producer-runner"
CANARY_DURATION_IN_SECONDS = 120
COLD_STARTUP_DELAY_IN_SECONDS = 60 * 60
MIN_RETRY_DELAY_IN_SECONDS = 60
FRAGMENT_SIZE_IN_BYTES = 1048576

JAR_FILES=""
CLASSPATH_VALUES=""


COMMON_PARAMS = [
    string(name: 'AWS_KVS_LOG_LEVEL', value: "2"),
    string(name: 'GIT_URL', value: GIT_URL),
    string(name: 'GIT_HASH', value: GIT_HASH),
    string(name: 'MIN_RETRY_DELAY_IN_SECONDS', value: MIN_RETRY_DELAY_IN_SECONDS.toString()),
]

def getJobLastBuildTimestamp(job) {
    def timestamp = 0
    def lastBuild = job.getLastBuild()

    if (lastBuild != null) {
        timestamp = lastBuild.getTimeInMillis()
    }

    return timestamp
}

def cancelJob(jobName) {
    def job = Jenkins.instance.getItemByFullName(jobName)

    echo "Tear down ${jobName}"
    job.setDisabled(true)
    job.getBuilds()
       .findAll({ build -> build.isBuilding() })
       .each({ build -> 
            echo "Kill $build"
            build.doKill()
        })
}

def findRunners() {
    def filterClosure = { item -> item.getDisplayName().startsWith(RUNNER_JOB_NAME_PREFIX) }
    return Jenkins.instance
                    .getAllItems(Job.class)
                    .findAll(filterClosure)
}

NEXT_AVAILABLE_RUNNER = null
ACTIVE_RUNNERS = [] 

pipeline {
    agent {
        label 'master'
    }

    options {
        disableConcurrentBuilds()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout([$class: 'GitSCM', branches: [[name: GIT_HASH ]],
                          userRemoteConfigs: [[url: GIT_URL]]])
            }
        }

        stage('Update runners') {
            stages {
                stage("Find the next available runner and current active runners") {
                    steps {
                        script {
                            def runners = findRunners()
                            def nextRunner = null 
                            def oldestTimestamp = Long.MAX_VALUE

                            // find the least active runner
                            runners.each {
                                def timestamp = getJobLastBuildTimestamp(it)
                                if ((it.isDisabled() || !it.isBuilding()) && timestamp < oldestTimestamp) {
                                    nextRunner = it
                                    oldestTimestamp = timestamp
                                }
                            }

                            if (nextRunner == null) {
                                error "There's no available runner"
                            }

                            NEXT_AVAILABLE_RUNNER = nextRunner.getDisplayName()
                            echo "Found next available runner: ${NEXT_AVAILABLE_RUNNER}"

                            ACTIVE_RUNNERS = runners.findAll({ item -> item != nextRunner && (!item.isDisabled() || item.isBuilding()) })
                                                    .collect({ item -> item.getDisplayName() })
                            echo "Found current active runners: ${ACTIVE_RUNNERS}"
                        }
                    }
                }
            
                stage("Spawn new runners") {
                    steps {
                        script {
                            echo "New runner: ${NEXT_AVAILABLE_RUNNER}"
                            Jenkins.instance.getItemByFullName(NEXT_AVAILABLE_RUNNER).setDisabled(false)
                        }

                        // TODO: Use matrix to spawn runners
                        build(
                            job: NEXT_AVAILABLE_RUNNER,
                            parameters: COMMON_PARAMS + [
                                string(name: 'CANARY_DURATION_IN_SECONDS', value: CANARY_DURATION_IN_SECONDS.toString()),
                                string(name: 'PRODUCER_NODE_LABEL', value: "producer-uw2"),
                                string(name: 'CONSUMER_NODE_LABEL', value: "consumer-uw2"),
                                string(name: 'CANARY_TYPE', value: "realtime"),
                                string(name: 'RUNNER_LABEL', value: "Periodic"),
                                string(name: 'FRAGMENT_SIZE_IN_BYTES', value: FRAGMENT_SIZE_IN_BYTES.toString()),
                            ],
                            wait: false
                        )

                        build(
                            job: NEXT_AVAILABLE_RUNNER,
                            parameters: COMMON_PARAMS + [
                                string(name: 'CANARY_DURATION_IN_SECONDS', value: CANARY_DURATION_IN_SECONDS.toString()),
                                string(name: 'PRODUCER_NODE_LABEL', value: "producer-uw2"),
                                string(name: 'CONSUMER_NODE_LABEL', value: "consumer-uw2"),
                                string(name: 'CANARY_TYPE', value: "realtime"),
                                string(name: 'RUNNER_LABEL', value: "Periodic"),
                                string(name: 'FRAGMENT_SIZE_IN_BYTES', value: FRAGMENT_SIZE_IN_BYTES.toString()),
                            ],
                            wait: false
                        )
                    }
                }

                stage("Tear down old runners") {
                    when {
                        expression { return ACTIVE_RUNNERS.size() > 0 }
                    }

                    steps {
                        script {
                            try {
                                sleep COLD_STARTUP_DELAY_IN_SECONDS
                            } catch(err) {
                                // rollback the newly spawned runner
                                echo "Rolling back ${NEXT_AVAILABLE_RUNNER}"
                                cancelJob(NEXT_AVAILABLE_RUNNER)
                                throw err
                            }

                            for (def runner in ACTIVE_RUNNERS) {
                                cancelJob(runner)
                            }
                        }
                    }
                }
            }
        }
    }
}
