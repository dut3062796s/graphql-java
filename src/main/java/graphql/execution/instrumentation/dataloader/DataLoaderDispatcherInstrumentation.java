package graphql.execution.instrumentation.dataloader;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.ExecutionStrategy;
import graphql.execution.instrumentation.ExecutionStrategyInstrumentationContext;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentation;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationDeferredFieldParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderRegistry;
import org.dataloader.stats.Statistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * This graphql {@link graphql.execution.instrumentation.Instrumentation} will dispatch
 * all the contained {@link org.dataloader.DataLoader}s when each level of the graphql
 * query is executed.
 *
 * This allows you to use {@link org.dataloader.DataLoader}s in your {@link graphql.schema.DataFetcher}s
 * to optimal loading of data.
 *
 * @see org.dataloader.DataLoader
 * @see org.dataloader.DataLoaderRegistry
 */
public class DataLoaderDispatcherInstrumentation extends SimpleInstrumentation {

    private static final Logger log = LoggerFactory.getLogger(DataLoaderDispatcherInstrumentation.class);

    private final DataLoaderRegistry dataLoaderRegistry;
    private final DataLoaderDispatcherInstrumentationOptions options;
    private final FieldLevelTrackingApproach fieldLevelTrackingApproach;
    private final CombinedCallsApproach combinedCallsApproach;

    /**
     * You pass in a registry of N data loaders which will be {@link org.dataloader.DataLoader#dispatch() dispatched} as
     * each level of the query executes.
     *
     * @param dataLoaderRegistry the registry of data loaders that will be dispatched
     */
    public DataLoaderDispatcherInstrumentation(DataLoaderRegistry dataLoaderRegistry) {
        this(dataLoaderRegistry, DataLoaderDispatcherInstrumentationOptions.newOptions());
    }

    /**
     * You pass in a registry of N data loaders which will be {@link org.dataloader.DataLoader#dispatch() dispatched} as
     * each level of the query executes.
     *
     * @param dataLoaderRegistry the registry of data loaders that will be dispatched
     * @param options            the options to control the behaviour
     */
    public DataLoaderDispatcherInstrumentation(DataLoaderRegistry dataLoaderRegistry, DataLoaderDispatcherInstrumentationOptions options) {
        this.dataLoaderRegistry = dataLoaderRegistry;
        this.options = options;
        this.fieldLevelTrackingApproach = new FieldLevelTrackingApproach(log, dataLoaderRegistry);
        this.combinedCallsApproach = new CombinedCallsApproach(log, dataLoaderRegistry);
    }


    @Override
    public InstrumentationState createState() {
        if (options.isUseCombinedCallsApproach()) {
            return combinedCallsApproach.createState();
        } else {
            return fieldLevelTrackingApproach.createState();
        }
    }


    @Override
    public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher, InstrumentationFieldFetchParameters parameters) {
        DataLoaderDispatcherInstrumentationState state = parameters.getInstrumentationState();
        if (state.isAggressivelyBatching()) {
            return dataFetcher;
        }
        //
        // currently only AsyncExecutionStrategy with DataLoader and hence this allows us to "dispatch"
        // on every object if its not using aggressive batching for other execution strategies
        // which allows them to work if used.
        return (DataFetcher<Object>) environment -> {
            Object obj = dataFetcher.get(environment);
            immediatelyDispatch();
            return obj;
        };
    }

    private void immediatelyDispatch() {
        if (options.isUseCombinedCallsApproach()) {
            combinedCallsApproach.dispatch();
        } else {
            fieldLevelTrackingApproach.dispatch();
        }
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginExecuteOperation(InstrumentationExecuteOperationParameters parameters) {
        ExecutionStrategy queryStrategy = parameters.getExecutionContext().getQueryStrategy();
        if (!(queryStrategy instanceof AsyncExecutionStrategy)) {
            DataLoaderDispatcherInstrumentationState state = parameters.getInstrumentationState();
            state.setAggressivelyBatching(false);
        }
        if (options.isUseCombinedCallsApproach()) {
            return combinedCallsApproach.beginExecuteOperation();
        } else {
            return new SimpleInstrumentationContext<>();
        }
    }

    @Override
    public ExecutionStrategyInstrumentationContext beginExecutionStrategy(InstrumentationExecutionStrategyParameters parameters) {
        if (options.isUseCombinedCallsApproach()) {
            return new ExecutionStrategyInstrumentationContext() {
                @Override
                public void onDispatched(CompletableFuture<ExecutionResult> result) {

                }

                @Override
                public void onCompleted(ExecutionResult result, Throwable t) {

                }
            };
        } else {
            return fieldLevelTrackingApproach.beginExecutionStrategy(parameters);
        }
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginDeferredField(InstrumentationDeferredFieldParameters parameters) {
        if (options.isUseCombinedCallsApproach()) {
            return combinedCallsApproach.beginDeferredField();
        } else {
            return fieldLevelTrackingApproach.beginDeferredField(parameters);
        }
    }

    @Override
    public InstrumentationContext<Object> beginFieldFetch(InstrumentationFieldFetchParameters parameters) {
        if (options.isUseCombinedCallsApproach()) {
            return new SimpleInstrumentationContext<>();
        } else {
            return fieldLevelTrackingApproach.beginFieldFetch(parameters);
        }
    }

    @Override
    public CompletableFuture<ExecutionResult> instrumentExecutionResult(ExecutionResult executionResult, InstrumentationExecutionParameters parameters) {
        if (!options.isIncludeStatistics()) {
            return CompletableFuture.completedFuture(executionResult);
        }
        Map<Object, Object> currentExt = executionResult.getExtensions();
        Map<Object, Object> statsMap = new LinkedHashMap<>();
        statsMap.putAll(currentExt == null ? Collections.emptyMap() : currentExt);
        Map<Object, Object> dataLoaderStats = buildStatsMap();
        statsMap.put("dataloader", dataLoaderStats);

        log.debug("Data loader stats : {}", dataLoaderStats);

        return CompletableFuture.completedFuture(new ExecutionResultImpl(executionResult.getData(), executionResult.getErrors(), statsMap));
    }

    private Map<Object, Object> buildStatsMap() {
        Statistics allStats = dataLoaderRegistry.getStatistics();
        Map<Object, Object> statsMap = new LinkedHashMap<>();
        statsMap.put("overall-statistics", allStats.toMap());

        Map<Object, Object> individualStatsMap = new LinkedHashMap<>();

        for (String dlKey : dataLoaderRegistry.getKeys()) {
            DataLoader<Object, Object> dl = dataLoaderRegistry.getDataLoader(dlKey);
            Statistics statistics = dl.getStatistics();
            individualStatsMap.put(dlKey, statistics.toMap());
        }

        statsMap.put("individual-statistics", individualStatsMap);

        return statsMap;
    }
}