# jenkins-spark-notifier-plugin

[usage-wiki](https://wiki.jenkins-ci.org/display/JENKINS/Spark+Notifier+Plugin)

# Developer instructions
Build HPI to install plugin
```
./gradlew clean jpi
```
Artifact will be located @ build/libs/cisco-spark-notifier.hpi

# Wish list
* Global credential setting: job/step credential optional to ovverride global
* Display junit test failure in message
* Fail the Build/Post-Build Step on error sending message (pipeline implemented already)
* Better default message(s)

# Usage Note
Please note that the environment variable JAVA_OPTS is present and has been specified for the correct proxy settings (if there is one). For cisco lab use, please utilize the following, proxy-wsa.esl.cisco.com:80. 
