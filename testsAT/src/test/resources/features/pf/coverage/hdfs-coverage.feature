@rest
Feature: [Spark HDFS Coverage] HDFS Coverage tests

  Background:
    Given I open a ssh connection to '${DCOS_CLI_HOST}' with user 'root' and password 'stratio'
    #Check dispatcher and spark-coverage are deployed
    Then in less than '20' seconds, checking each '10' seconds, the command output 'dcos task | grep "${SPARK_FW_NAME}\." | grep R | wc -l' contains '1'
    Then in less than '20' seconds, checking each '10' seconds, the command output 'dcos task | grep spark-coverage | grep R | wc -l' contains '1'

  Scenario:[Spark HDFS coverage][01] Deploy HDFS spark job

    #Obtain mesos master
    Given I open a ssh connection to '${DCOS_IP}' with user 'root' and password 'stratio'
    Given I run 'getent hosts leader.mesos | awk '{print $1}'' in the ssh connection and save the value in environment variable 'MESOS_MASTER'

    #Now launch the work
    Given I set sso token using host '${CLUSTER_ID}.labs.stratio.com' with user 'admin' and password '1234'
    And I securely send requests to '${CLUSTER_ID}.labs.stratio.com:443'

    When I send a 'POST' request to '/service/${SPARK_FW_NAME}/v1/submissions/create' based on 'schemas/pf/SparkCoverage/hdfs_curl.json' as 'json' with:
      |   $.appResource  |  UPDATE  | http://spark-coverage.marathon.mesos:9000/jobs/hdfs-${COVERAGE_VERSION}.jar | n/a     |
      |   $.sparkProperties['spark.jars']  |  UPDATE  | http://spark-coverage.marathon.mesos:9000/jobs/hdfs-${COVERAGE_VERSION}.jar | n/a     |
      |   $.sparkProperties['spark.mesos.executor.docker.image']  |  UPDATE  | ${SPARK_DOCKER_IMAGE}:${STRATIO_SPARK_VERSION} | n/a     |
      |   $.sparkProperties['spark.mesos.driverEnv.SPARK_SECURITY_HDFS_CONF_URI']  |  UPDATE  | http://spark-coverage.marathon.mesos:9000/configs/${CLUSTER_ID} | n/a     |

    Then the service response status must be '200' and its response must contain the text '"success" : true'

    #Save the driver launched id
    Then I save the value from field in service response 'submissionId' in variable 'driverHdfs'

    #Check output is correct
    Given I open a ssh connection to '${DCOS_CLI_HOST}' with user 'root' and password 'stratio'
    Then in less than '200' seconds, checking each '10' seconds, the command output 'dcos task log !{driverHdfs} stdout --lines=1000 --completed | grep "Checking if elements read are the same that elements written: true" | wc -l' contains '1'

    #Wait until finished
    Then in less than '200' seconds, checking each '10' seconds, the command output 'curl -s !{MESOS_MASTER}:5050/frameworks | jq '.frameworks[] | select(.name == "${SPARK_FW_NAME}") | .completed_tasks |  map(select(.name | contains ("AT-hdfs"))) | map(select(.id == "!{driverHdfs}")) | .[] | .state' | grep "TASK_FINISHED" | wc -l' contains '1'


    #Check has finished correctly
    Then in less than '10' seconds, checking each '5' seconds, the command output 'curl -s !{MESOS_MASTER}:5050/frameworks | jq '.frameworks[] | select(.name == "${SPARK_FW_NAME}") | .completed_tasks |  map(select(.name | contains ("AT-hdfs"))) | map(select(.id == "!{driverHdfs}")) | .[] | .statuses' | grep "TASK_FAILED"  | wc -l' contains '0'
    Then in less than '10' seconds, checking each '5' seconds, the command output 'curl -s !{MESOS_MASTER}:5050/frameworks | jq '.frameworks[] | select(.name == "${SPARK_FW_NAME}") | .completed_tasks |  map(select(.name | contains ("AT-hdfs"))) | map(select(.id == "!{driverHdfs}")) | .[] | .statuses' | grep "TASK_RUNNING"  | wc -l' contains '1'
    Then in less than '10' seconds, checking each '5' seconds, the command output 'curl -s !{MESOS_MASTER}:5050/frameworks | jq '.frameworks[] | select(.name == "${SPARK_FW_NAME}") | .completed_tasks |  map(select(.name | contains ("AT-hdfs"))) | map(select(.id == "!{driverHdfs}")) | .[] | .statuses' | grep "TASK_FINISHED"  | wc -l' contains '1'