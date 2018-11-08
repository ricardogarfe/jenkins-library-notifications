import java.util.Optional

import groovy.json.JsonOutput
import groovy.transform.Field

import hudson.tasks.junit.CaseResult
import hudson.tasks.test.AbstractTestResultAction
import hudson.model.Actionable
import hudson.Util

def state = null

@Field String author = ""
@Field String message = ""
@Field String testSummary = ""
@Field Integer total = 0
@Field Integer failed = 0
@Field Integer skipped = 0
@Field String branchName = ""


def notifySlack(text, channel, attachments, slackHook) {
    def slackURL = slackHook
    def jenkinsIcon = 'https://wiki.jenkins-ci.org/download/attachments/2916393/logo.png'

    def payload = JsonOutput.toJson([text: text,
        channel: channel,
        username: "Jenkins",
        icon_url: jenkinsIcon,
        attachments: attachments
    ])

    sh "curl -X POST --data-urlencode \'payload=${payload}\' ${slackURL}"
}

def getGitAuthor () {
    def commit = sh(returnStdout: true, script: 'git rev-parse HEAD')
    author = sh(returnStdout: true, script: "git --no-pager show -s --format='%an' ${commit}").trim()
}

def getLastCommitMessage () {
    message = sh(returnStdout: true, script: 'git log -1 --pretty=%B').trim()
}

def getBranchName() {
    if ("${state.env.GIT_BRANCH}") {
        branchName = "${state.env.GIT_BRANCH}"
    } else {
        branchName = "${state.scmVars.GIT_BRANCH}"
    }
}

def getTestSummary() {
    def testResultAction = state.currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
    def summary = ""

    if (testResultAction != null) {
        total = testResultAction.getTotalCount()
        failed = testResultAction.getFailCount()
        skipped = testResultAction.getSkipCount()

        summary = "Passed: " + (total - failed - skipped)
        summary = summary + (", Failed: " + failed)
        summary = summary + (", Skipped: " + skipped)
    } else {
        summary = "No tests found"
    }
    testSummary = summary
}

def getFailedTests() {
    def testResultAction = state.currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
    def failedTestsString = "```"

    if (testResultAction != null) {
        def failedTests = testResultAction.getFailedTests()

        if (failedTests.size() > 9) {
            failedTests = failedTests.subList(0, 8)
        }

        failedTests.each {
            failedTestsString = failedTestsString + it.getDisplayName() + "\n\n"
        }

        failedTestsString = failedTestsString + "```"
    }
    return failedTestsString
}

def populateGlobalVariables() {
    getLastCommitMessage()
    getGitAuthor()
    getBranchName()
    getTestSummary()
}

def generateTestResultAttachment(script) {
    state = script  
    populateGlobalVariables()
    
    def buildColor = state.currentBuild.result == null ? "good" : "warning"
    def buildStatus = state.currentBuild.result == null ? "Success" : state.currentBuild.result
    def jobName = "${state.env.JOB_BASE_NAME}"

    def attachments = [
        [
            title: "${jobName}, build #${state.env.BUILD_NUMBER}",
            title_link: "${state.env.BUILD_URL}",
            color: "${buildColor}",
            text: "${buildStatus}\n${author}",
            "mrkdwn_in": ["fields"],
            fields: [
                [
                    title: "Branch",
                    value: "${branchName}",
                    short: true
                ],
                [
                    title: "Test Results",
                    value: "${testSummary}",
                    short: true
                ],
                [
                    title: "Last Commit",
                    value: "${message}",
                    short: true
                ]
            ]
        ]
    ]

    if (!"0".equalsIgnoreCase("${failed}")) {

        buildStatus = "Unstable"
        buildColor = "warning"
        def failedTestsString = getFailedTests()

        def failedTestAttachment = [
                title: "Failed Tests",
                color: "${buildColor}",
                text: "${failedTestsString}",
                "mrkdwn_in": ["text"],
                footer: "Tests",
                ts: "${System.currentTimeMillis()/1000}"
            ]

        attachments.add(failedTestAttachment)
    }

    return attachments
}

def generateErrorMessage (script, exception) {
    state = script
    populateGlobalVariables()

    def buildStatus = "Failed"
    def jobName = "${state.env.JOB_BASE_NAME}"

    def attachments = [
        [
            title: "${jobName}, build #${state.env.BUILD_NUMBER}",
            title_link: "${state.env.BUILD_URL}",
            color: "danger",
            author_name: "${author}",
            text: "${buildStatus}",
            fields: [
                [
                    title: "Branch",
                    value: "${branchName}",
                    short: true
                ],
                [
                    title: "Test Results",
                    value: "${testSummary}",
                    short: true
                ],
                [
                    title: "Last Commit",
                    value: "${message}",
                    short: true
                ],
                [
                    title: "Error",
                    value: "${exception}",
                    short: false
                ]
            ],
            footer: "Tests",
            ts: "${System.currentTimeMillis()/1000}"
        ]
    ]
    return attachments
}
