package services.S3MHandlersAnalysis.implementations

import main.exception.TravisHelperException
import main.project.Project
import main.util.GithubHelper
import main.util.TravisHelper

import static main.app.MiningFramework.arguments

class ProjectProcessor implements main.interfaces.ProjectProcessor {

    private GithubHelper githubHelper
    private TravisHelper travisHelper

    @Override
    ArrayList<Project> processProjects(ArrayList<Project> projects) {
        if (arguments.providedAccessKey()) {
            githubHelper = new GithubHelper(arguments.getAccessKey())
            travisHelper = new TravisHelper(arguments.getAccessKey())
            println "Processing projects"

            ArrayList<Project> projectsForks = new ArrayList<Project>()
            for (project in projects) {
                if (project.isRemote()) {
                    def forkedProject = githubHelper.fork(project)
                    String path = "${githubHelper.URL}/${forkedProject.full_name}"
                    Project projectFork = new Project(project.getName(), path)

                    try {
                        keepTryingToEnableTravisProject(projectFork, 10)

                        projectsForks.add(projectFork)
                    } catch (TravisHelperException e) {
                        println "Couldn't enable travis for project: ${projectFork}, skipping it"
                        println e.getMessage()
                    }
                } else {
                    println "${project.getName()} is not remote and cant be forked"
                }
            }

            return projectsForks
        }
        return projects
    }

    private void keepTryingToEnableTravisProject(Project project, int maxNumberOfTries) {
        /* This is a workaround to a limitation in travis api
        * You have to wait and sync multiple times to a project
        * become available
        */
        try {
            configureTravisProject(project)
        } catch (TravisHelperException e) {
            travisHelper.syncAndWait()
            if (maxNumberOfTries > 0) {
                keepTryingToEnableTravisProject(project, maxNumberOfTries - 1)
            } else {
                println e.getMessage()
                throw new TravisHelperException("Number of sync tries exceeded")
            }
        }
    }

    private void configureTravisProject(Project project) {
        String[] ownerAndName = project.getOwnerAndName()
        Map travisProject = travisHelper.getProject(ownerAndName[0], ownerAndName[1])
        travisHelper.enableTravis(travisProject.id as Integer)
        travisHelper.addEnvironmentVariable(travisProject.id as Integer, "GITHUB_TOKEN", arguments.getAccessKey())
    }

}
