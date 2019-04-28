package services

import main.project.Project

import java.net.HttpURLConnection
import main.util.HttpHelper


class GithubHelper {

    private final String API_URL = "https://api.github.com"
    private final String RAW_CONTENT_URL = "https://raw.githubusercontent.com"
    private String accessKey;
    
    public GithubHelper (String accessKey) {
        this.accessKey = accessKey
    }

    public getUser() {
        String url = "${API_URL}/user"
        HttpURLConnection connection = HttpHelper.requestToApi(url, "GET", this.accessKey)
        
        def resBody = HttpHelper.responseToJSON(connection.getInputStream())
        if (connection.getResponseMessage() != "OK") {
            throw new GithubHelperException("Http request returned an error ${responseMessage}")
        }
        return resBody
    }

    public String fork (Project project) {
        if (project.isRemote()) {
            try {
                String[] projectNameAndOwner = project.getOwnerAndName()
                String projectOwner = projectNameAndOwner[0]
                String projectName = projectNameAndOwner[1]

                String url = "${API_URL}/repos/${projectOwner}/${projectName}/forks"
                String responseMessage = HttpHelper.requestToApi(url, "POST", this.accessKey).getResponseMessage();
                if (responseMessage != "Accepted") {
                    throw new GithubHelperException("Http request returned an error ${responseMessage}")
                }
                return responseMessage
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new GithubHelperException("Error parsing project remote")
            }
        }
    }

    public getFile (String projectOwner, String projectName, String path) {
        String url = getContentApiUrl(projectOwner, projectName, path)
        HttpURLConnection response = HttpHelper.requestToApi(url, "GET", this.accessKey)
        String responseMessage = response.getResponseMessage()
        if (responseMessage != "OK") {
            throw new GithubHelperException("Http request returned an error ${responseMessage}")
        }
        
        def result = HttpHelper.responseToJSON(response.getInputStream())
        result.content = HttpHelper.convertToUTF8(result.content)

        return result
    }

    public updateFile(String projectOwner, String projectName, String path, String fileSha, String content, String commitMessage) {
        String url = getContentApiUrl(projectOwner, projectName, path)
        HttpURLConnection connection = HttpHelper.requestToApi(url, "PUT", this.accessKey)
        connection.setRequestProperty("Content-type", "application/json");
        connection.setDoOutput(true);
                
        def message = [
            message: commitMessage, 
            content: HttpHelper.convertToBase64(content), 
            sha: fileSha
        ]

            
        PrintStream printStream = new PrintStream(connection.getOutputStream());
        printStream.println(HttpHelper.jsonToString(message));

        String responseMessage = connection.getResponseMessage()

        if (responseMessage != "OK") {
            throw new GithubHelperException("Http request returned an error ${responseMessage}")
        }
        return responseMessage
    }

    private getContentApiUrl(String projectOwner, String projectName, String path) {
        return "${API_URL}/repos/${projectOwner}/${projectName}/contents/${path}"
    }

}