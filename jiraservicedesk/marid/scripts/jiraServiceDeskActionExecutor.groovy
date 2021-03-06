import com.ifountain.opsgenie.client.http.OpsGenieHttpClient
import com.ifountain.opsgenie.client.util.ClientConfiguration
import com.ifountain.opsgenie.client.util.JsonUtils
import org.apache.http.HttpHeaders

LOG_PREFIX = "[${mappedAction}]:";
logger.info("${LOG_PREFIX} Will execute [${mappedAction}] for alertId ${params.alertId}");

CONF_PREFIX = "jiraservicedesk.";
HTTP_CLIENT = createHttpClient();
try {
    String url = params.url
    if (url == null || "".equals(url)) {
        url = _conf("url", true)
    }
    String issueKey = params.IssueKey
    String projectKey = params.key
    if (projectKey == null || "".equals(projectKey)) {
        projectKey = _conf("projectKey", true)
    }
    String issueTypeName = params.issueTypeName
    if (issueTypeName == null || "".equals(issueTypeName)) {
        issueTypeName = _conf("issueType", true)
    }


    String username = params.username
    String password = params.password

    if (username == null || "".equals(username)) {
        username = _conf("username", true)
    }
    if (password == null || "".equals(password)) {
        password = _conf("password", true)
    }

    Map contentTypeHeader = [:]
    contentTypeHeader[HttpHeaders.CONTENT_TYPE] = "application/json"
    def authString = (username + ":" + password).getBytes().encodeBase64().toString()
    contentTypeHeader[HttpHeaders.AUTHORIZATION] = "Basic ${authString}".toString()
    contentTypeHeader[HttpHeaders.ACCEPT_LANGUAGE] = "application/json"

    def contentParams = [:]
    def fields = [:]
    def project = [:]
    def issuetype = [:]
    def transitions = [:]
    def resolution = [:]

    String resultUrl = url + "/rest/api/2/issue"
    if (mappedAction == "addCommentToIssue") {
        contentParams.put("body", params.body)
        resultUrl += "/" + issueKey + "/comment"
    } else if (mappedAction == "createIssue") {
        issuetype.put("name", issueTypeName)
        project.put("key", projectKey)
        fields.put("project", project)
        fields.put("issuetype", issuetype)
        fields.put("summary", params.summary)
        fields.put("description", params.description)
        String toLabel = "ogAlias:" + params.alias
        fields.put("labels", Collections.singletonList(toLabel.replaceAll("\\s", "")))
        contentParams.put("fields", fields)
    } else if (mappedAction == "resolveIssue") {
        resultUrl += "/" + issueKey + "/transitions"
        resolution.put("name", "Done")
        fields.put("resolution", resolution)
        transitions.put("id", getTransitionId(contentTypeHeader, resultUrl, "Resolved"))
        contentParams.put("transition", transitions)
        contentParams.put("fields", fields)
    }
    def responseBody = sendActionsToJiraServiceDesk(params.projectKey, contentParams, contentTypeHeader, resultUrl)
    if (mappedAction == "createIssue") {
        addIssueKeyToAlertDetails(params, responseBody)
    }
}
catch (Exception e) {
    logger.error(e.getMessage(), e)
}
finally {
    HTTP_CLIENT.close()
}

void addIssueKeyToAlertDetails(Map alertProps, Map responseBody) {
    String issueKey = responseBody.key
    String alertId = alertProps.alertId
    if (responseBody != null) {
        opsgenie.addDetails(["id": alertId, "details": ["issueKey": issueKey]])
    } else {
        logger.debug("${LOG_PREFIX} Jira Service Desk response body is null.")
    }
}

def sendActionsToJiraServiceDesk(String projectKey, Map<String, String> postParams, Map headers, String jiraServiceDeskUrl) {

    def response = HTTP_CLIENT.post(jiraServiceDeskUrl, JsonUtils.toJson(postParams), headers)
    def responseMap = [:]
    def body = response.content
    if (body != null) {
        responseMap = JsonUtils.parse(body)
        if (response.getStatusCode() < 400) {
            logger.info("${LOG_PREFIX} Successfully executed at Jira Service Desk.");
            logger.debug("${LOG_PREFIX} Jira Service Desk response: ${response.getContentAsString()}")
        } else {
            logger.error("cem " + projectKey)
            logger.error("${LOG_PREFIX} Could not execute at Jira Service Desk; response: ${response.statusCode} ${response.getContentAsString()}");
        }
    }
    return responseMap
}

def getTransitionId(Map headers, String jiraServiceDeskUrl, String transitionName) {

    def transitionId = null
    def response = HTTP_CLIENT.get(jiraServiceDeskUrl, [:], headers)
    def body = response.content
    if (body != null) {
        def responseMap = JsonUtils.parse(body)
        if (response.getStatusCode() < 400) {
            def transitionList = responseMap.transitions
            for (transition in transitionList) {
                def to = transition.to
                if (transitionName == to.name) {
                    transitionId = transition.id
                }
            }
            logger.info("${LOG_PREFIX} Successfully executed at Jira Service Desk.");
            logger.debug("${LOG_PREFIX} Jira Service Desk response: ${response.getContentAsString()}")
        } else {
            logger.error("${LOG_PREFIX} Could not execute at Jira Service Desk; response: ${response.statusCode} ${response.getContentAsString()}");
        }
    }
    if (transitionId != null) {
        return transitionId
    } else {
        logger.debug("Transition id is null.")
    }

}


def createHttpClient() {
    def timeout = _conf("http.timeout", false);
    if (timeout == null) {
        timeout = 30000;
    } else {
        timeout = timeout.toInteger();
    }

    ClientConfiguration clientConfiguration = new ClientConfiguration().setSocketTimeout(timeout)
    return new OpsGenieHttpClient(clientConfiguration)
}

def _conf(confKey, boolean isMandatory) {
    def confVal = conf[CONF_PREFIX + confKey]
    if (isMandatory && confVal == null) {
        def errorMessage = "${LOG_PREFIX} Skipping action, Mandatory Conf item ${CONF_PREFIX + confKey} is missing. Check your marid conf file.";
        throw new Exception(errorMessage);
    }
    return confVal
}