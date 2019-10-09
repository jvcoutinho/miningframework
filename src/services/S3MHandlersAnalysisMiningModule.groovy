package services

@Grab('com.google.inject:guice:4.2.2')
import com.google.inject.*
import com.google.inject.multibindings.Multibinder
import main.interfaces.CommitFilter
import main.interfaces.DataCollector
import main.interfaces.OutputProcessor
import main.interfaces.ProjectProcessor

public class S3MHandlersAnalysisMiningModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder<DataCollector> dataCollectorBinder = Multibinder.newSetBinder(binder(), DataCollector.class)

        dataCollectorBinder.addBinding().to(S3MHandlersAnalysisDifferentMergesCollector.class)
        dataCollectorBinder.addBinding().to(S3MHandlersAnalysisConflictAnalyser.class)
        //dataCollectorBinder.addBinding().to(BuildRequester.class)

        bind(CommitFilter.class).to(S3MHandlersAnalysisCommitFilter.class)
        bind(ProjectProcessor.class).to(S3MHandlersAnalysisProjectProcessor.class)
        bind(OutputProcessor.class).to(S3MHandlersAnalysisOutputProcessor.class)
    }

}