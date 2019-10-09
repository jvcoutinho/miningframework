package services

import groovy.json.JsonSlurper
import main.app.MiningFramework
import main.interfaces.DataCollector
import main.project.MergeCommit
import main.project.Project
import main.util.ProcessRunner
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.sql.Time
import java.util.regex.Matcher

import static groovy.io.FileType.*

class S3MHandlersAnalysisConflictAnalyser implements DataCollector {

    private class WaitCompletion implements Runnable {

        private static final String TRAVIS_API = 'https://api.travis-ci.org'

        Project project
        String branchName

        WaitCompletion(Project project, String branchName) {
            this.project = project
            this.branchName = branchName
        }

        @Override
        void run() {
            boolean buildCompleted = false
            while(!buildCompleted) {
                println "Build ${branchName}: waiting 30 seconds"
                sleep(30000)

                Object builds = getTravisBuilds()
                if(builds != null) {
                    builds.each { build ->
                        if (build['state'] == 'errored' || build['state'] == 'passed') {
                            println build['state']

                            buildCompleted = true
                        }
                    }
                }

            }
        }

        private Object getTravisBuilds() {
            HttpURLConnection connection = new URL("https://api.travis-ci.com/repo/11044989/builds?branch.name=15c0bd5_semis_build_branch").openConnection()
            connection.setRequestProperty("Travis-API-Version", "3")
            connection.setRequestProperty("Authorization", "token wwfkf1TFZV4qddepZ5IPIA")

            if(connection.getResponseCode() == 200) {
                String response = connection.getInputStream().getText()
                return new JsonSlurper().parseText(response)['builds']
            } else {
                println "Build ${branchName}: connection error"
                return null
            }
        }
    }

    private enum ConflictArea {
        None,
        Left,
        Right
    }

    private List<Thread> threads

    @Override
    void collectData(Project project, MergeCommit mergeCommit) {
        threads = new ArrayList<>()
        Path mergeCommitOutputPath = Paths.get(MiningFramework.arguments.getOutputPath(), project.getName(), mergeCommit.getSHA())
        if(Files.exists(mergeCommitOutputPath)) {
            for (Tuple2<Path, Path> differentHandlersResults in getDifferentHandlersResultsPaths(mergeCommitOutputPath)) {
                analyseConflicts(project, mergeCommit, differentHandlersResults)
            }
        }
        threads.each { it.join() }
    }

    private void analyseConflicts(Project project, MergeCommit mergeCommit, Tuple2<Path, Path> differentHandlersResults) {
        Path currentHandlerResult = differentHandlersResults.getV1()
        Path legacyHandlerResult = differentHandlersResults.getV2()

        List<MergeConflict> legacyHandlerResultMergeConflicts = extractMergeConflicts(legacyHandlerResult)
        List<MergeConflict> currentHandlerResultMergeConflicts = extractMergeConflicts(currentHandlerResult)

        // combine conflicts or check if zero (?)
        if(legacyHandlerResultMergeConflicts == currentHandlerResultMergeConflicts) {
            println "Running build"
            runTravisBuild(project, mergeCommit, legacyHandlerResult)
            //runTravisBuild(project, mergeCommit, currentHandlerResult)
        }
    }

    private void runTravisBuild(Project project, MergeCommit mergeCommit, Path mergeFile) {
        assert (MiningFramework.arguments.providedAccessKey())
        String branchName = "${mergeCommit.getSHA().take(7)}_${mergeFile.getFileName().toString().take(5)}_build_branch"
        triggerTravisBuild(project, mergeCommit, mergeFile, branchName)
        spawnWaitCompletionThread(project, branchName)
    }

    private void spawnWaitCompletionThread(Project project, String branchName) {
        Thread thread = new Thread(new WaitCompletion(project, branchName))
        threads.add(thread)
        thread.start()
    }

    private void triggerTravisBuild(Project project, MergeCommit mergeCommit, Path mergeFile, String branchName) {

        createBranchFromCommit(project, mergeCommit, branchName)
        replaceMergeFile(project, mergeCommit, mergeFile)
        commitChanges(project, 'Trigger build')
        pushBranch(project, branchName)
    }

    private static void pushBranch(Project project, String branchName) {
        Process gitPush = ProcessRunner.runProcess(project.getPath(), 'git', 'push', '-u', 'origin', branchName)
        gitPush.getInputStream().eachLine { println it }
        gitPush.waitFor()
    }

    private static void commitChanges(Project project, String commitMessage) {
        Process gitCommit = ProcessRunner.runProcess(project.getPath(), 'git', 'commit', '-a', '-m', commitMessage)
        gitCommit.getInputStream().eachLine { println it }
        gitCommit.waitFor()
    }

    private static boolean replaceMergeFile(Project project, MergeCommit mergeCommit, Path mergeFile) {
        Path mergeCommitOutputPath = Paths.get(MiningFramework.arguments.getOutputPath(), project.getName(), mergeCommit.getSHA())
        Path filePath = Paths.get(project.getPath()).resolve(mergeCommitOutputPath.relativize(mergeFile).getParent().getParent())
        Files.copy(mergeFile, filePath, StandardCopyOption.REPLACE_EXISTING)
    }

    private static void createBranchFromCommit(Project project, MergeCommit mergeCommit, String branchName) {
        Process gitCheckout = ProcessRunner.runProcess(project.getPath(), 'git', 'checkout', '-b', branchName, mergeCommit.getSHA())
        gitCheckout.getInputStream().eachLine { println it }
        gitCheckout.waitFor()
    }

    private static List<MergeConflict> extractMergeConflicts(Path file) {
        List<MergeConflict> mergeConflicts = new ArrayList<MergeConflict>()

        StringBuilder leftConflictingContent = new StringBuilder()
        StringBuilder rightConflictingContent = new StringBuilder()

        ConflictArea conflictArea
        conflictArea = ConflictArea.None

        Iterator<String> mergeCodeLines = FileUtils.readLines(file.toFile(), Charset.defaultCharset()).iterator()
        while(mergeCodeLines.hasNext()) {
            String line = mergeCodeLines.next()

            /* See the following conditionals as a state machine. */
            if(StringUtils.deleteWhitespace(line) == MergeConflict.MINE_CONFLICT_MARKER && conflictArea == ConflictArea.None) {
                conflictArea = ConflictArea.Left
            }

            else if(StringUtils.deleteWhitespace(line) == MergeConflict.CHANGE_CONFLICT_MARKER && conflictArea == ConflictArea.Left) {
                conflictArea = ConflictArea.Right;
            }

            else if(StringUtils.deleteWhitespace(line) == MergeConflict.YOURS_CONFLICT_MARKER && conflictArea == ConflictArea.Right) {
                mergeConflicts.add(new MergeConflict(leftConflictingContent.toString(), rightConflictingContent.toString()))
                conflictArea = ConflictArea.None
            }

            else {
                switch (conflictArea) {
                    case ConflictArea.Left:
                        leftConflictingContent.append(line).append('\n')
                        break
                    case ConflictArea.Right:
                        rightConflictingContent.append(line).append('\n')
                        break
                    default: // not in conflict area
                        break
                }
            }
        }
        return mergeConflicts
    }

    private static List<Tuple2<Path, Path>> getDifferentHandlersResultsPaths(Path mergeCommitOutputPath) {
        List<Path> currentHandlerMergeResults = new ArrayList<>()
        List<Path> legacyHandlerMergeResults = new ArrayList<>()
        mergeCommitOutputPath.traverse(type: FILES, nameFilter: ~/semistructured-(LEGACY|CURRENT)\.java/) {
            if(it.getFileName().toString().contains("LEGACY"))
                legacyHandlerMergeResults.add(it)
            else
                currentHandlerMergeResults.add(it)
        }
        return zip(currentHandlerMergeResults, legacyHandlerMergeResults)
    }

    private static List<Tuple2<Path, Path>> zip(ArrayList<Path> array1, ArrayList<Path> array2) {
        List<Tuple2<Path, Path>> result = new ArrayList<>()
        for (int i = 0; i < array1.size(); i++) {
            result.add(new Tuple2<>(array1.get(i), array2.get(i)))
        }
        return result
    }

}
