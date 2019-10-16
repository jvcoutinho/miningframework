package services

import com.sun.scenario.effect.impl.prism.PrCropPeer
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

    private final Path TO_PUSH_REPOSITORY = Paths.get("E:/Projetos/merge-tools")

    private enum ConflictArea {
        None,
        Left,
        Right
    }

    public enum Handler {
        Legacy,
        Current
    }

    private List<Thread> threads

    @Override
    void collectData(Project project, MergeCommit mergeCommit) {
        Path mergeCommitOutputPath = Paths.get(MiningFramework.arguments.getOutputPath(), project.getName(), mergeCommit.getSHA())
        if(Files.exists(mergeCommitOutputPath)) {
            threads = new ArrayList<>()
            for (Tuple2<Path, Path> differentHandlersResults in getDifferentHandlersResultsPaths(mergeCommitOutputPath)) {
                analyseConflicts(project, mergeCommit, differentHandlersResults)
            }
            threads.each { it.join() }
            pushResults(mergeCommitOutputPath)
        }
    }

    void pushResults(Path mergeCommitOutputPath) {
        String legacyHandlerBuildConflictsSummary = getBuildConflictsSummary()
        FileUtils.writeStringToFile(mergeCommitOutputPath.getParent().resolve("buildConflicts.txt").toFile(), legacyHandlerBuildConflictsSummary, Charset.defaultCharset())

        FileUtils.copyDirectoryToDirectory(mergeCommitOutputPath.getParent().toFile(), TO_PUSH_REPOSITORY.resolve("handlers-comparison/results").toFile())

        Process process = ProcessRunner.runProcess(TO_PUSH_REPOSITORY.toString(), "git", "add", ".")
        process.getInputStream().eachLine {}
        process.waitFor()

        process = ProcessRunner.runProcess(TO_PUSH_REPOSITORY.toString(), "git", "commit", "-m", "Pushing results")
        process.getInputStream().eachLine {}
        process.waitFor()

        process = ProcessRunner.runProcess(TO_PUSH_REPOSITORY.toString(), "git", "push")
        process.getInputStream().eachLine {}
        process.waitFor()
    }

    private String getBuildConflictsSummary() {
        StringBuilder output = new StringBuilder()
        output.append(getBuildConflictsSummary("legacy handler", TravisCommunicator.legacyHandlerErrors))
        output.append(getBuildConflictsSummary("current handler", TravisCommunicator.currentHandlerErrors))
        return output.toString()
    }

    private String getBuildConflictsSummary(String handlerVersion, List<String> erroredFilePaths) {
        StringBuilder summary = new StringBuilder()
        summary.append("Number of ${handlerVersion} build conflicts: ${erroredFilePaths.size()}\n")
        erroredFilePaths.each { erroredFilePath ->
            summary.append(erroredFilePath).append('\n')
        }
        return summary.toString()
    }

    private void analyseConflicts(Project project, MergeCommit mergeCommit, Tuple2<Path, Path> differentHandlersResults) {
        Path currentHandlerResult = differentHandlersResults.getV1()
        Path legacyHandlerResult = differentHandlersResults.getV2()

        List<MergeConflict> legacyHandlerResultMergeConflicts = extractMergeConflicts(legacyHandlerResult)
        List<MergeConflict> currentHandlerResultMergeConflicts = extractMergeConflicts(currentHandlerResult)

        println legacyHandlerResultMergeConflicts
        println currentHandlerResultMergeConflicts
        // combine conflicts or check if zero (?)
        if(legacyHandlerResultMergeConflicts != currentHandlerResultMergeConflicts) {
            println "Running build"
            if(legacyHandlerResultMergeConflicts.isEmpty())
                runTravisBuild(project, mergeCommit, legacyHandlerResult, Handler.Legacy)
            else if(currentHandlerResultMergeConflicts.isEmpty())
                runTravisBuild(project, mergeCommit, currentHandlerResult, Handler.Current)
        }
    }

    private void runTravisBuild(Project project, MergeCommit mergeCommit, Path mergeFile, Handler version) {
        assert (MiningFramework.arguments.providedAccessKey())
        String branchName = "${mergeCommit.getSHA().take(8)}_${mergeFile.getFileName().toString().take(5)}_build_branch"
        triggerTravisBuild(project, mergeCommit, mergeFile, branchName)
        spawnWaitCompletionThread(project, branchName, version, mergeFile)
    }

    private void spawnWaitCompletionThread(Project project, String branchName, Handler version, Path mergeFile) {
        Thread thread = new Thread(new TravisCommunicator(project, branchName, version, mergeFile))
        threads.add(thread)
        thread.start()
    }

    private void triggerTravisBuild(Project project, MergeCommit mergeCommit, Path mergeFile, String branchName) {

        createBranchFromCommit(project, mergeCommit, branchName)
        replaceMergeFile(project, mergeCommit, mergeFile)
        replaceDifferentFromExistingFiles(project, mergeCommit)
        commitChanges(project, 'Trigger build')
        pushBranch(project, branchName)
    }

    private static void replaceDifferentFromExistingFiles(Project project, MergeCommit mergeCommit) {
        Path differentFromExistingDirectoryPath = Paths.get(MiningFramework.arguments.getOutputPath(), project.getName(), mergeCommit.getSHA(), "differentFromExisting")
        if(Files.exists(differentFromExistingDirectoryPath)) {
            differentFromExistingDirectoryPath.traverse(type: FILES, nameFilter: ~/.*\.java/) {
                Files.copy(it, Paths.get(project.getPath()).resolve(differentFromExistingDirectoryPath.relativize(it)), StandardCopyOption.REPLACE_EXISTING)
            }
        }

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
            if(StringUtils.deleteWhitespace(line) == StringUtils.deleteWhitespace(MergeConflict.MINE_CONFLICT_MARKER) && conflictArea == ConflictArea.None) {
                conflictArea = ConflictArea.Left
            }

            else if(StringUtils.deleteWhitespace(line) == StringUtils.deleteWhitespace(MergeConflict.CHANGE_CONFLICT_MARKER) && conflictArea == ConflictArea.Left) {
                conflictArea = ConflictArea.Right;
            }

            else if(StringUtils.deleteWhitespace(line) == StringUtils.deleteWhitespace(MergeConflict.YOURS_CONFLICT_MARKER) && conflictArea == ConflictArea.Right) {
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
