package services

import main.interfaces.CommitFilter
import main.project.MergeCommit
import main.project.Project

class S3MHandlersAnalysisCommitFilter implements CommitFilter {

    @Override
    boolean applyFilter(Project project, MergeCommit mergeCommit) {
        return true
    }

}
