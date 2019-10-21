package services

import com.google.inject.internal.cglib.proxy.$MethodProxy
import com.sun.scenario.effect.Merge
import com.sun.xml.internal.ws.handler.HandlerException
import main.app.MiningFramework
import main.arguments.Arguments
import main.interfaces.DataCollector
import main.project.MergeCommit
import main.project.Project
import main.util.ProcessRunner
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils

import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class S3MHandlersAnalysisDifferentMergesCollector implements DataCollector {

    private enum Handler {
        Renaming,
        InitializationBlock
    }

    // To extend this analysis to other handlers, you must (1) add its correspondent argument to the following switch statement;
    private String getHandlerArgument(Handler handler) {
        switch (handler) {
            case Handler.Renaming:
                return '-hmcrdov'
            default:
                return ''
        }
    }

    private Path S3MBinaryPath = Paths.get(System.getProperty("user.home"), "Desktop", "S3M.jar")

    @Override
    void collectData(Project project, MergeCommit mergeCommit) {
        for(Tuple4<Path, Path, Path, Path> mergeScenario in retrieveMergeScenarios(project, mergeCommit)) {
            // (2) invoke runDifferentHandlers changing the last parameter
            runDifferentHandlers(project, mergeCommit, mergeScenario, Handler.Renaming)
           // runAndCompareStaticBlockHandlers()
        }
    }

    private void runDifferentHandlers(Project project, MergeCommit mergeCommit, Tuple4<Path, Path, Path, Path> mergeScenario, Handler handler) {
        Tuple2<Path, Path> legacyHandlerMergeFiles = runS3M(mergeScenario, getHandlerArgument(handler))
        Tuple2<Path, Path> currentHandlerMergeFiles = runS3M(mergeScenario, '')

        if(semistructuredMergeResultsAreDifferent(legacyHandlerMergeFiles, currentHandlerMergeFiles)) {
            storeScenario(project, mergeCommit, mergeScenario, legacyHandlerMergeFiles, currentHandlerMergeFiles, handler)
        } else if(differentFromExistingMerge(project, mergeScenario, currentHandlerMergeFiles)) {
            storeFile(project, mergeCommit, mergeScenario, currentHandlerMergeFiles)
        }
    }

    private void storeFile(Project project, MergeCommit mergeCommit, Tuple4<Path, Path, Path, Path> mergeScenario, Tuple2<Path, Path> currentHandlerMergeFiles) {
        Path fileDirectory = Paths.get(MiningFramework.arguments.getOutputPath(), project.getName(), mergeCommit.getSHA(), mergeScenario.getV4().toString())
        fileDirectory.toFile().mkdirs()

        Files.copy(currentHandlerMergeFiles.getV1(), fileDirectory.resolve("semistructured.java"), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(currentHandlerMergeFiles.getV2(), fileDirectory.resolve("unstructured.java"), StandardCopyOption.REPLACE_EXISTING)
        Files.move(mergeScenario.getV1(), fileDirectory.resolve("left.java"), StandardCopyOption.REPLACE_EXISTING)
        Files.move(mergeScenario.getV2(), fileDirectory.resolve("base.java"), StandardCopyOption.REPLACE_EXISTING)
        Files.move(mergeScenario.getV3(), fileDirectory.resolve("right.java"), StandardCopyOption.REPLACE_EXISTING)
    }

    private boolean differentFromExistingMerge(Project project, Tuple4<Path, Path, Path, Path> mergeScenario, Tuple2<Path, Path> currentHandlerMergeFiles) {
        return StringUtils.deleteWhitespace(currentHandlerMergeFiles.getV2().text) != StringUtils.deleteWhitespace(currentHandlerMergeFiles.getV1().text)
    }

    private boolean semistructuredMergeResultsAreDifferent(Tuple2<Path, Path> legacyHandlerMergeFiles, Tuple2<Path, Path> currentHandlerMergeFiles) {
        return StringUtils.deleteWhitespace(legacyHandlerMergeFiles.getV1().text) != StringUtils.deleteWhitespace(currentHandlerMergeFiles.getV1().text)
    }

    private void storeScenario(Project project, MergeCommit mergeCommit, Tuple4 <Path, Path, Path, Path> mergeScenario, Tuple2<Path, Path> legacyHandlerMergeFiles, Tuple2<Path, Path> currentHandlerMergeFiles, Handler handler) {
        Path scenarioPath = Paths.get(MiningFramework.arguments.getOutputPath(), project.getName(), mergeCommit.getSHA(), mergeScenario.getV4().toString(), getHandlerDirectory(handler))
        scenarioPath.toFile().mkdirs()

        Files.move(mergeScenario.getV1(), scenarioPath.resolve("left.java"), StandardCopyOption.REPLACE_EXISTING)
        Files.move(mergeScenario.getV2(), scenarioPath.resolve("base.java"), StandardCopyOption.REPLACE_EXISTING)
        Files.move(mergeScenario.getV3(), scenarioPath.resolve("right.java"), StandardCopyOption.REPLACE_EXISTING)

        Files.move(legacyHandlerMergeFiles.getV1(), scenarioPath.resolve("semistructured-LEGACY.java"), StandardCopyOption.REPLACE_EXISTING)
        Files.move(currentHandlerMergeFiles.getV1(), scenarioPath.resolve("semistructured-CURRENT.java"), StandardCopyOption.REPLACE_EXISTING)

        Files.move(legacyHandlerMergeFiles.getV2(), scenarioPath.resolve("unstructured.java"), StandardCopyOption.REPLACE_EXISTING)
    }

    private String getHandlerDirectory(Handler handler) {
        return handler.toString()
    }

    private Tuple2<Path, Path> runS3M(Tuple4<Path, Path, Path, Path> mergeScenario, String handlerArgument) {
        File outputFile = File.createTempFile(mergeScenario.getV2().getFileName().toString(), "")
        outputFile.deleteOnExit()

        Process S3M = ProcessRunner.runProcess(S3MBinaryPath.getParent().toString(), "java", "-jar", "S3M.jar", "-f", mergeScenario.getV1().toString(), mergeScenario.getV2().toString(), mergeScenario.getV3().toString(), "-o", outputFile.toString(), handlerArgument)
        S3M.getInputStream().eachLine {}
        S3M.waitFor()
        return new Tuple2(outputFile.toPath(), getUnstructuredMergeFile(outputFile.toPath()))
    }

    private Path getUnstructuredMergeFile(Path semistructuredMergeFile) {
        return semistructuredMergeFile.getParent().resolve(semistructuredMergeFile.getFileName().toString() + '.merge')
    }

    private List<Tuple4<Path, Path, Path, Path>> retrieveMergeScenarios(Project project, MergeCommit mergeCommit) {
        List<Tuple4<Path, Path, Path, Path>> mergeScenarios = new ArrayList<>()
        for(String changedFile in getJavaFiles(project, mergeCommit)) {
            Path leftFile = getFilePath(project, changedFile, mergeCommit.getLeftSHA())
            Path baseFile = getFilePath(project, changedFile, mergeCommit.getAncestorSHA())
            Path rightFile = getFilePath(project, changedFile, mergeCommit.getRightSHA())
            mergeScenarios.add(new Tuple4<>(leftFile, baseFile, rightFile, Paths.get(changedFile)))
        }
        return mergeScenarios
    }

    private Path getFilePath(Project project, String changedFile, String commitSHA) {
        File file = File.createTempFile("${commitSHA}:${changedFile}", "")
        file.deleteOnExit()
        file << getFileContent(project, changedFile, commitSHA)
        return file.toPath()
    }

    private String getFileContent(Project project, String changedFile, String commitSHA) {
        StringBuilder fileContent = new StringBuilder()

        Process gitShow = ProcessRunner.runProcess(project.getPath(), "git", "show", "${commitSHA}:${changedFile}")
        gitShow.getInputStream().eachLine {
            fileContent.append(it).append("\n")
        }

        return fileContent.toString()
    }

    private List<String> getJavaFiles(Project project, MergeCommit mergeCommit) {
        List<String> changedFiles = new ArrayList<>();
        Process gitDiffTree = ProcessRunner.runProcess(project.getPath(), "git", "ls-tree", "--name-only", "-r", mergeCommit.getSHA())
        gitDiffTree.getInputStream().eachLine {
            if(isJavaFile(it)) {
                changedFiles.add(getFilePath(it))
            }
        }
        return changedFiles
    }

    private boolean isJavaFile(String gitDiffTreeLine) {
        return gitDiffTreeLine.endsWith(".java")
    }

    private boolean isModifiedFile(String gitDiffTreeLine) {
        return gitDiffTreeLine.charAt(0) == 'M' as char
    }

    private String getFilePath(String gitDiffTreeLine) {
        // M         name.extension
        return gitDiffTreeLine
    }
}
