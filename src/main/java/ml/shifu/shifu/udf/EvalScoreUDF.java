/*
 * Copyright [2012-2014] PayPal Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.shifu.shifu.udf;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.logicalLayer.schema.Schema.FieldSchema;
import org.apache.pig.impl.util.UDFContext;
import org.apache.pig.tools.pigstats.PigStatusReporter;
import org.encog.ml.BasicML;

import ml.shifu.guagua.util.NumberFormatUtils;
import ml.shifu.shifu.column.NSColumn;
import ml.shifu.shifu.container.CaseScoreResult;
import ml.shifu.shifu.container.obj.ColumnConfig;
import ml.shifu.shifu.container.obj.RawSourceData.SourceType;
import ml.shifu.shifu.core.ModelRunner;
import ml.shifu.shifu.core.Scorer;
import ml.shifu.shifu.core.dtrain.CommonConstants;
import ml.shifu.shifu.core.dtrain.gs.GridSearch;
import ml.shifu.shifu.core.model.ModelSpec;
import ml.shifu.shifu.fs.PathFinder;
import ml.shifu.shifu.fs.ShifuFileUtils;
import ml.shifu.shifu.udf.norm.CategoryMissingNormType;
import ml.shifu.shifu.udf.norm.PrecisionType;
import ml.shifu.shifu.util.CommonUtils;
import ml.shifu.shifu.util.Constants;
import ml.shifu.shifu.util.Environment;
import ml.shifu.shifu.util.ModelSpecLoaderUtils;
import ml.shifu.shifu.util.MultiClsTagPredictor;

/**
 * Calculate the score for each evaluation data
 */
public class EvalScoreUDF extends AbstractEvalUDF<Tuple> {

    private static final String SHIFU_EVAL_SCORE_MULTITHREAD = "shifu.eval.score.multithread";

    private static final String SHIFU_NN_OUTPUT_FIRST_HIDDENLAYER = "shifu.nn.output.first.hiddenlayer";

    private static final String SHIFU_NN_OUTPUT_HIDDENLAYER_INDEX = "shifu.nn.output.hiddenlayer.index";

    private static final String SCHEMA_PREFIX = "shifu::";

    private ModelRunner modelRunner;
    private String[] headers;

    private double maxScore = Double.MIN_VALUE;
    private double minScore = Double.MAX_VALUE;

    private List<String> modelScoreNames;
    private Map<String, List<String>> subModelScoreNames;
    private String scale;

    /**
     * A simple weight exception validation: if over 5000 throw exceptions
     */
    private int weightExceptions;

    /**
     * For neural network, if output the first
     */
    private boolean outputFirstHiddenLayer = false;

    /**
     * Hidden layer output index
     */
    private int outputHiddenLayerIndex = 0;

    /**
     * Helper fields for #outputFirstHiddenLayer
     */
    private List<Integer> hiddenNodeList;

    /**
     * Splits for filter expressions
     */
    private int segFilterSize = 0;

    /**
     * If multi threading scoring for multiple models
     */
    private boolean isMultiThreadScoring = false;

    private boolean isLinearTarget = false;

    /**
     * There is header for input or not?
     */
    private boolean isCsvFormat = false;

    private MultiClsTagPredictor mcPredictor;

    private int[] mtlTagColumnNums;
    @SuppressWarnings("rawtypes")
    private Set[] mtlPosTagSet;
    @SuppressWarnings("rawtypes")
    private Set[] mtlNegTagSet;
    @SuppressWarnings("rawtypes")
    private Set[] mtlTagSet;
    private List<Map<Integer, Map<String, Integer>>> mtlCiMapList;
    private List<List<Set<String>>> mtlSetTagsList;
    protected List<List<ColumnConfig>> mtlColumnConfigLists;
    private boolean isMultiTask = false;

    private int modelCnt;

    @SuppressWarnings("rawtypes")
    private Map subModelsCnt;

    private String currWgtNameInMTL;

    private PrecisionType precisionType;

    public EvalScoreUDF(String source, String pathModelConfig, String pathColumnConfig, String evalSetName)
            throws IOException {
        this(source, pathModelConfig, pathColumnConfig, evalSetName, Integer.toString(Scorer.DEFAULT_SCORE_SCALE));
    }

    @SuppressWarnings("unchecked")
    public EvalScoreUDF(String source, String pathModelConfig, String pathColumnConfig, String evalSetName,
            String scale) throws IOException {
        super(source, pathModelConfig, pathColumnConfig, evalSetName);
        if(evalConfig.getModelsPath() != null) {
            // renew columnConfig
            this.columnConfigList = ShifuFileUtils.searchColumnConfig(evalConfig, columnConfigList);
        }

        this.isCsvFormat = StringUtils.isBlank(evalConfig.getDataSet().getHeaderPath());
        this.headers = CommonUtils.getFinalHeaders(modelConfig, evalConfig);

        String filterExpressions;
        if(UDFContext.getUDFContext() != null && UDFContext.getUDFContext().getJobConf() != null) {
            filterExpressions = UDFContext.getUDFContext().getJobConf().get(Constants.SHIFU_SEGMENT_EXPRESSIONS);
        } else {
            filterExpressions = Environment.getProperty(Constants.SHIFU_SEGMENT_EXPRESSIONS);
        }

        if(StringUtils.isNotBlank(filterExpressions)) {
            this.segFilterSize = CommonUtils.split(filterExpressions,
                    Constants.SHIFU_STATS_FILTER_EXPRESSIONS_DELIMETER).length;
        }

        String precision = getUdfProperty(Constants.SHIFU_PRECISION_TYPE);
        if(StringUtils.isNotBlank(precision)) {
            this.precisionType = PrecisionType
                    .of(getUdfProperty(Constants.SHIFU_PRECISION_TYPE, PrecisionType.FLOAT32.toString()));
        }

        // move model runner construction in exec to avoid OOM error in client side if model is too big like RF
        // TODO not to load model but only to check model file cnt
        this.modelScoreNames = ModelSpecLoaderUtils.getBasicModelScoreNames(modelConfig, evalConfig,
                evalConfig.getDataSet().getSource());
        this.modelCnt = this.modelScoreNames.size();
        this.subModelScoreNames = ModelSpecLoaderUtils.getSubModelScoreNames(modelConfig, this.columnConfigList,
                evalConfig, evalConfig.getDataSet().getSource());
        if(this.subModelScoreNames != null && this.subModelScoreNames.size() > 0) {
            this.subModelsCnt = new HashMap<>();
            for(Entry<String, List<String>> entry: this.subModelScoreNames.entrySet()) {
                this.subModelsCnt.put(entry.getKey(), entry.getValue().size());
            }
        }

        if(modelConfig.isClassification()) {
            if(modelConfig.getTrain().isOneVsAll()) {
                if(modelConfig.getTags().size() == 2) {
                    // one vs all, just return first model score name
                    this.modelScoreNames = this.modelScoreNames.subList(0, 1);
                } else {
                    this.modelScoreNames = this.modelScoreNames.subList(0, modelConfig.getTags().size());
                }
            } else {
                if(modelConfig.getTags().size() == 2) {
                    // native binary
                    this.modelScoreNames = this.modelScoreNames.subList(0, 1);
                }
            }
            this.mcPredictor = new MultiClsTagPredictor(this.modelConfig);
        }

        this.scale = (this.modelConfig.isLinearRegression() ? "1" : scale); // no scale for linear model

        log.info("Run eval " + evalConfig.getName() + " with " + this.modelScoreNames.size() + " model(s).");

        // only check if output first hidden layer in regression and NN
        if(modelConfig.isRegression() && Constants.NN.equalsIgnoreCase(modelConfig.getAlgorithm())) {
            GridSearch gs = new GridSearch(modelConfig.getTrain().getParams(),
                    modelConfig.getTrain().getGridConfigFileContent());
            Map<String, Object> validParams = this.modelConfig.getTrain().getParams();
            if(gs.hasHyperParam()) {
                validParams = gs.getParams(0);
            }
            hiddenNodeList = (List<Integer>) validParams.get(CommonConstants.NUM_HIDDEN_NODES);

            // only check when hidden layer > 0
            if(this.hiddenNodeList.size() > 0) {
                if(UDFContext.getUDFContext() != null && UDFContext.getUDFContext().getJobConf() != null) {
                    this.outputFirstHiddenLayer = Boolean.TRUE.toString().equalsIgnoreCase(UDFContext.getUDFContext()
                            .getJobConf().get(SHIFU_NN_OUTPUT_FIRST_HIDDENLAYER, Boolean.FALSE.toString()));
                    this.outputHiddenLayerIndex = UDFContext.getUDFContext().getJobConf()
                            .getInt(SHIFU_NN_OUTPUT_HIDDENLAYER_INDEX, 0);
                } else {
                    this.outputFirstHiddenLayer = Boolean.TRUE.toString().equalsIgnoreCase(
                            Environment.getProperty(SHIFU_NN_OUTPUT_FIRST_HIDDENLAYER, Boolean.FALSE.toString()));
                    this.outputHiddenLayerIndex = Environment.getInt(SHIFU_NN_OUTPUT_HIDDENLAYER_INDEX, 0);
                }

                if(outputFirstHiddenLayer) {
                    this.outputHiddenLayerIndex = 1;
                }

                if(this.outputHiddenLayerIndex == 1) {
                    this.outputFirstHiddenLayer = true;
                }

                if(this.outputHiddenLayerIndex < -1 || this.outputHiddenLayerIndex > this.hiddenNodeList.size()) {
                    throw new IllegalArgumentException("outputHiddenLayerIndex should in [-1, hidden layers]");
                }

                // TODO validation
                log.debug("DEBUG: outputHiddenLayerIndex is " + outputHiddenLayerIndex);
            }
        }

        if(UDFContext.getUDFContext() != null && UDFContext.getUDFContext().getJobConf() != null) {
            this.isMultiThreadScoring = UDFContext.getUDFContext().getJobConf().getBoolean(SHIFU_EVAL_SCORE_MULTITHREAD,
                    false);
        } else {
            this.isMultiThreadScoring = Environment.getBoolean(SHIFU_EVAL_SCORE_MULTITHREAD, false);
        }

        this.isLinearTarget = CommonUtils.isLinearTarget(modelConfig, columnConfigList);

        this.isMultiTask = modelConfig.isMultiTask();
        this.currWgtNameInMTL = evalConfig.getDataSet().getWeightColumnName();
        if(this.isMultiTask) {
            int mtlIndex = -1;
            if(UDFContext.getUDFContext() != null && UDFContext.getUDFContext().getJobConf() != null) {
                mtlIndex = NumberFormatUtils
                        .getInt(UDFContext.getUDFContext().getJobConf().get(CommonConstants.MTL_INDEX), -1);
            } else {
                // "when do local initilization mtlIndex is -1, set to 0 to pass");
                mtlIndex = 0;
            }
            modelConfig.setMtlIndex(mtlIndex);
            boolean multiWeightsInMTL = modelConfig.isMultiWeightsInMTL(evalConfig.getDataSet().getWeightColumnName());
            if(multiWeightsInMTL) {
                this.currWgtNameInMTL = modelConfig
                        .getMultiTaskWeightColumnNames(evalConfig.getDataSet().getWeightColumnName())
                        .get(this.modelConfig.getMtlIndex());
            }
            initMultTaskConfigs();
        }
    }

    private void initMultTaskConfigs() throws IOException {
        this.mtlColumnConfigLists = new ArrayList<>();
        List<String> tagColumns = this.modelConfig.getMultiTaskTargetColumnNames();
        mtlTagColumnNums = new int[tagColumns.size()];
        mtlPosTagSet = new Set[tagColumns.size()];
        mtlNegTagSet = new Set[tagColumns.size()];
        mtlTagSet = new Set[tagColumns.size()];
        mtlCiMapList = new ArrayList<>();
        mtlSetTagsList = new ArrayList<>();

        for(int i = 0; i < tagColumns.size(); i++) {
            List<ColumnConfig> ccList = CommonUtils.loadColumnConfigList(
                    new PathFinder(this.modelConfig).getMTLColumnConfigPath(SourceType.HDFS, i), SourceType.HDFS);
            this.mtlColumnConfigLists.add(ccList);
            // FIXME, from eval config ???
            mtlTagColumnNums[i] = CommonUtils.getTargetColumnNum(ccList);
            mtlPosTagSet[i] = new HashSet<>(this.modelConfig.getMTLPosTags(i));
            mtlNegTagSet[i] = new HashSet<>(this.modelConfig.getMTLNegTags(i));
            mtlTagSet[i] = new HashSet<>(this.modelConfig.getMTLTags(i));
            mtlCiMapList.add(buildCateIndeMap(ccList));
            mtlSetTagsList.add(modelConfig.getMTLSetTags(i));
        }
    }

    private Map<Integer, Map<String, Integer>> buildCateIndeMap(List<ColumnConfig> columnConfigList) {
        Map<Integer, Map<String, Integer>> categoricalIndexMap = new HashMap<Integer, Map<String, Integer>>();
        for(ColumnConfig config: columnConfigList) {
            if(config.isCategorical()) {
                Map<String, Integer> map = new HashMap<String, Integer>();
                if(config.getBinCategory() != null) {
                    for(int i = 0; i < config.getBinCategory().size(); i++) {
                        List<String> catValues = CommonUtils.flattenCatValGrp(config.getBinCategory().get(i));
                        for(String cval: catValues) {
                            map.put(cval, i);
                        }
                    }
                }
                categoricalIndexMap.put(config.getColumnNum(), map);
            }
        }
        return categoricalIndexMap;
    }

    @SuppressWarnings("deprecation")
    public Tuple exec(Tuple input) throws IOException {
        if(isCsvFormat) {
            String firstCol = ((input.get(0) == null) ? "" : input.get(0).toString());
            if(this.headers[0].equals(CommonUtils.normColumnName(firstCol))) {
                // Column value == Column Header? It's the first line of file?
                // TODO what to do if the column value == column name? ...
                return null;
            }
        }

        long start = System.currentTimeMillis();
        if(this.modelRunner == null) {
            // here to initialize modelRunner, this is moved from constructor to here to avoid OOM in client side.
            // UDF in pig client will be initialized to get some metadata issues
            List<BasicML> models = ModelSpecLoaderUtils.loadBasicModels(modelConfig, evalConfig,
                    evalConfig.getDataSet().getSource(), evalConfig.getGbtConvertToProb(),
                    evalConfig.getGbtScoreConvertStrategy());
            if(this.isMultiTask) {
                this.modelRunner = new ModelRunner(modelConfig, mtlColumnConfigLists, this.headers,
                        evalConfig.getDataSet().getDataDelimiter(), models, this.outputHiddenLayerIndex,
                        this.isMultiThreadScoring, this.getCategoryMissingNormType(), this.isMultiTask,
                        this.precisionType);
            } else {
                this.modelRunner = new ModelRunner(modelConfig, columnConfigList, this.headers,
                        evalConfig.getDataSet().getDataDelimiter(), models, this.outputHiddenLayerIndex,
                        this.isMultiThreadScoring, this.getCategoryMissingNormType(), this.precisionType);
            }

            // FIXME MTL not supported in sub models
            List<ModelSpec> subModels = ModelSpecLoaderUtils.loadSubModels(modelConfig, this.columnConfigList,
                    evalConfig, evalConfig.getDataSet().getSource(), evalConfig.getGbtConvertToProb(),
                    evalConfig.getGbtScoreConvertStrategy());
            if(CollectionUtils.isNotEmpty(subModels)) {
                for(ModelSpec modelSpec: subModels) {
                    this.modelRunner.addSubModels(modelSpec, this.isMultiThreadScoring);
                }
            }

            // reset models in classification case
            if(modelConfig.isClassification()) {
                int modelsCnt = 0;
                if(modelConfig.getTrain().isOneVsAll()) {
                    if(modelConfig.getTags().size() == 2) {
                        // one vs. all, modelCnt is 1
                        modelsCnt = 1;
                    } else {
                        modelsCnt = modelConfig.getTags().size();
                    }
                } else {
                    if(modelConfig.getTags().size() == 2) {
                        // native binary
                        modelsCnt = 1;
                    }
                }
                // reset models to
                if(modelsCnt > 0) {
                    models = models.subList(0, modelsCnt);
                }
                this.modelRunner = new ModelRunner(modelConfig, columnConfigList, this.headers,
                        evalConfig.getDataSet().getDataDelimiter(), models, this.outputHiddenLayerIndex,
                        this.isMultiThreadScoring, this.getCategoryMissingNormType(), this.precisionType);
            }
            this.modelRunner.setScoreScale(Integer.parseInt(this.scale));
            log.info("DEBUG: model cnt " + this.modelScoreNames.size() + " sub models cnt "
                    + modelRunner.getSubModelsCnt());
        }

        Map<NSColumn, String> rawDataNsMap = CommonUtils.convertDataIntoNsMap(input, this.headers);
        if(MapUtils.isEmpty(rawDataNsMap)) {
            return null;
        }

        String tag = CommonUtils.trimTag(rawDataNsMap.get(
                new NSColumn(modelConfig.getTargetColumnName(evalConfig, modelConfig.getTargetColumnName()))));

        // run model scoring
        long startTime = System.nanoTime();
        CaseScoreResult cs = modelRunner.computeNsData(rawDataNsMap);
        long runInterval = (System.nanoTime() - startTime) / 1000L;

        if(cs == null) {
            if(System.currentTimeMillis() % 100 == 0) {
                log.warn("Get null result, for input: " + input.toDelimitedString("|"));
            }
            return null;
        }

        String weight = "1.0";
        if(StringUtils.isNotBlank(this.currWgtNameInMTL)) {
            weight = rawDataNsMap.get(new NSColumn(this.currWgtNameInMTL));
        }

        incrementTagCounters(tag, weight, runInterval);

        Map<String, CaseScoreResult> subModelScores = cs.getSubModelScores();

        Tuple tuple = TupleFactory.getInstance().newTuple();
        tuple.append(tag);
        tuple.append(weight);

        if(this.isLinearTarget || modelConfig.isMultiTask() || modelConfig.isRegression()) {
            if(CollectionUtils.isNotEmpty(cs.getScores())) {
                appendModelScore(tuple, cs, true);
                if(this.outputHiddenLayerIndex != 0) {
                    appendFirstHiddenOutputScore(tuple, cs.getHiddenLayerScores(), true);
                }
            }

            if(MapUtils.isNotEmpty(subModelScores)) {
                Iterator<Map.Entry<String, CaseScoreResult>> iterator = subModelScores.entrySet().iterator();
                while(iterator.hasNext()) {
                    Map.Entry<String, CaseScoreResult> entry = iterator.next();
                    CaseScoreResult subCs = entry.getValue();
                    appendModelScore(tuple, subCs, false);
                }
            }
        } else {
            if(CollectionUtils.isNotEmpty(cs.getScores())) {
                appendSimpleScore(tuple, cs);
                tuple.append(this.mcPredictor.predictTag(cs).getTag());
            }

            if(MapUtils.isNotEmpty(subModelScores)) {
                Iterator<Map.Entry<String, CaseScoreResult>> iterator = subModelScores.entrySet().iterator();
                while(iterator.hasNext()) {
                    Map.Entry<String, CaseScoreResult> entry = iterator.next();
                    CaseScoreResult subCs = entry.getValue();
                    appendSimpleScore(tuple, subCs);
                }
            }
        }

        // append meta data
        List<String> metaColumns = evalConfig.getAllMetaColumns(modelConfig);
        if(CollectionUtils.isNotEmpty(metaColumns)) {
            for(String meta: metaColumns) {
                tuple.append(rawDataNsMap.get(new NSColumn(meta)));
            }
        }

        if(System.currentTimeMillis() % 1000 == 0L) {
            log.info("running time is " + (System.currentTimeMillis() - start) + " ms.");
        }
        return tuple;
    }

    @SuppressWarnings({ "unchecked", "unused" })
    private void incrementCounters(List<String> mtlTagValues, String weight, long runInterval) {
        assert mtlTagValues != null;
        incrementCommonCounters(runInterval);

        for(int i = 0; i < mtlTagValues.size(); i++) {
            incrementTagCounters(mtlTagValues.get(i), weight, runInterval, this.mtlPosTagSet[i], this.mtlNegTagSet[i],
                    i);
        }
    }

    private void appendFirstHiddenOutputScore(Tuple tuple, SortedMap<String, Double> hiddenLayerScores, boolean b) {
        for(Entry<String, Double> entry: hiddenLayerScores.entrySet()) {
            tuple.append(entry.getValue());
        }
    }

    /**
     * Append model scores (average, max, min, median, and scores) into tuple
     * 
     * @param tuple
     *            - Tuple to append
     * @param cs
     *            - CaseScoreResult
     * @param toGetMaxMin
     *            - to check max/min or not
     */
    private void appendModelScore(Tuple tuple, CaseScoreResult cs, boolean toGetMaxMin) {
        tuple.append(cs.getAvgScore());
        tuple.append(cs.getMaxScore());
        tuple.append(cs.getMinScore());
        tuple.append(cs.getMedianScore());

        for(double score: cs.getScores()) {
            tuple.append(score);
        }

        if(toGetMaxMin) {
            // get maxScore and minScore for such mapper or reducer
            if(cs.getMedianScore() > maxScore) {
                maxScore = cs.getMedianScore();
            }

            if(cs.getMedianScore() < minScore) {
                minScore = cs.getMedianScore();
            }
        }
    }

    /**
     * Append model scores into tuple
     * 
     * @param tuple
     *            - Tuple to append
     * @param cs
     *            - CaseScoreResulto
     */
    private void appendSimpleScore(Tuple tuple, CaseScoreResult cs) {
        for(int i = 0; i < cs.getScores().size(); i++) {
            tuple.append(cs.getScores().get(i));
        }
    }

    @Override
    public void finish() {
        // Since the modelRunner is initialized in execution, if there is no records for this reducer,
        // / the modelRunner may not initialized. It will cause NullPointerException
        if(this.modelRunner != null) {
            this.modelRunner.close();
        }

        if(modelConfig.isClassification()) {
            return;
        }

        // only for regression, in some cases like gbdt, it's regression score is not in [0,1], to do eval performance,
        // max and min score should be collected to set bounds.
        BufferedWriter writer = null;
        Configuration jobConf = UDFContext.getUDFContext().getJobConf();
        String scoreOutput = jobConf.get(Constants.SHIFU_EVAL_MAXMIN_SCORE_OUTPUT);

        log.debug("shifu.eval.maxmin.score.output is {}, job id is {}, task id is {}, attempt id is {}" + scoreOutput
                + " " + jobConf.get("mapreduce.job.id") + " " + jobConf.get("mapreduce.task.id") + " "
                + jobConf.get("mapreduce.task.partition") + " " + jobConf.get("mapreduce.task.attempt.id"));

        try {
            FileSystem fileSystem = FileSystem.get(jobConf);
            fileSystem.mkdirs(new Path(scoreOutput));
            String taskMaxMinScoreFile = scoreOutput + File.separator + "part-"
                    + jobConf.get("mapreduce.task.attempt.id");
            writer = ShifuFileUtils.getWriter(taskMaxMinScoreFile, SourceType.HDFS);
            writer.write(maxScore + "," + minScore);
        } catch (IOException e) {
            log.error("error in finish", e);
        } finally {
            if(writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    private void incrementTagCounters(String tag, String weight, long runModelInterval) {
        incrementCommonCounters(runModelInterval);
        incrementTagCounters(tag, weight, runModelInterval, posTagSet, negTagSet, -1);
    }

    @SuppressWarnings("deprecation")
    private void incrementTagCounters(String tag, String weight, long runModelInterval, Set<String> posTagSet,
            Set<String> negTagSet, int postfix) {
        if(tag == null || weight == null) {
            if(System.currentTimeMillis() % 50 == 0) {
                log.warn("tag is empty " + tag + " or weight is empty " + weight + ". And execution time - "
                        + runModelInterval);
            }
            return;
        }
        double dWeight = 1.0;
        if(StringUtils.isNotBlank(weight)) {
            try {
                dWeight = Double.parseDouble(weight);
            } catch (Exception e) {
                if(isPigEnabled(Constants.SHIFU_GROUP_COUNTER, "weight_exceptions")) {
                    PigStatusReporter.getInstance().getCounter(Constants.SHIFU_GROUP_COUNTER, "weight_exceptions")
                            .increment(1);
                }
                weightExceptions += 1;
                if(weightExceptions > 5000) {
                    throw new IllegalStateException(
                            "Please check weight column in eval, exceptional weight count is over 5000");
                }
            }
        }
        long weightLong = (long) (dWeight * Constants.EVAL_COUNTER_WEIGHT_SCALE);

        if(posTagSet.contains(tag)) {
            if(isPigEnabled(Constants.SHIFU_GROUP_COUNTER, Constants.COUNTER_POSTAGS)) {
                PigStatusReporter.getInstance()
                        .getCounter(Constants.SHIFU_GROUP_COUNTER,
                                postfix == -1 ? Constants.COUNTER_POSTAGS : Constants.COUNTER_POSTAGS + "_" + postfix)
                        .increment(1);
            }
            if(isPigEnabled(Constants.SHIFU_GROUP_COUNTER, Constants.COUNTER_WPOSTAGS)) {
                PigStatusReporter.getInstance()
                        .getCounter(Constants.SHIFU_GROUP_COUNTER,
                                postfix == -1 ? Constants.COUNTER_WPOSTAGS : Constants.COUNTER_WPOSTAGS + "_" + postfix)
                        .increment(weightLong);
            }
        }

        if(negTagSet.contains(tag)) {
            if(isPigEnabled(Constants.SHIFU_GROUP_COUNTER, Constants.COUNTER_NEGTAGS)) {
                PigStatusReporter.getInstance()
                        .getCounter(Constants.SHIFU_GROUP_COUNTER,
                                postfix == -1 ? Constants.COUNTER_NEGTAGS : Constants.COUNTER_NEGTAGS + "_" + postfix)
                        .increment(1);
            }
            if(isPigEnabled(Constants.SHIFU_GROUP_COUNTER, Constants.COUNTER_WNEGTAGS)) {
                PigStatusReporter.getInstance()
                        .getCounter(Constants.SHIFU_GROUP_COUNTER,
                                postfix == -1 ? Constants.COUNTER_WNEGTAGS : Constants.COUNTER_WNEGTAGS + "_" + postfix)
                        .increment(weightLong);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void incrementCommonCounters(long runModelInterval) {
        // update model run time for stats
        if(isPigEnabled(Constants.SHIFU_GROUP_COUNTER, Constants.TOTAL_MODEL_RUNTIME)) {
            PigStatusReporter.getInstance().getCounter(Constants.SHIFU_GROUP_COUNTER, Constants.TOTAL_MODEL_RUNTIME)
                    .increment(runModelInterval);
        }
        if(isPigEnabled(Constants.SHIFU_GROUP_COUNTER, Constants.COUNTER_RECORDS)) {
            PigStatusReporter.getInstance().getCounter(Constants.SHIFU_GROUP_COUNTER, Constants.COUNTER_RECORDS)
                    .increment(1);
        }
    }

    /**
     * output the schema for evaluation score
     */
    public Schema outputSchema(Schema input) {
        try {
            Schema tupleSchema = new Schema();
            tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + modelConfig.getTargetColumnName(evalConfig,
                            modelConfig.getTargetColumnName()), DataType.CHARARRAY));

            String weightName = StringUtils.isBlank(evalConfig.getDataSet().getWeightColumnName()) ? "weight"
                    : evalConfig.getDataSet().getWeightColumnName();
            tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + weightName, DataType.CHARARRAY));

            boolean isLinearTarget = CommonUtils.isLinearTarget(modelConfig, columnConfigList);

            if(isLinearTarget || this.isMultiTask || modelConfig.isRegression()) {
                if(this.modelCnt > 0) {
                    if(this.isMultiTask) {
                        // TODO REMOVEME
                        addModelSchema(tupleSchema, this.modelConfig.getMultiTaskTargetColumnNames().size(), "");
                    } else {
                        addModelSchema(tupleSchema, this.modelCnt, "");
                    }
                } else if(MapUtils.isEmpty(this.subModelsCnt)) {
                    throw new IllegalStateException("No any model found!");
                }

                if(this.outputHiddenLayerIndex != 0) {
                    for(int i = 0; i < this.modelScoreNames.size(); i++) {
                        // +1 to add bias neuron
                        for(int j = 0; j < (hiddenNodeList.get(outputHiddenLayerIndex - 1) + 1); j++) {
                            tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + this.modelScoreNames.get(i) + "_"
                                    + outputHiddenLayerIndex + "_" + j, DataType.DOUBLE));
                        }
                    }
                }

                if(MapUtils.isNotEmpty(this.subModelScoreNames)) {
                    Iterator<Map.Entry<String, List<String>>> iterator = this.subModelScoreNames.entrySet().iterator();
                    while(iterator.hasNext()) {
                        Map.Entry<String, List<String>> entry = iterator.next();
                        String modelName = entry.getKey();
                        List<String> subModelNames = entry.getValue();
                        if(CollectionUtils.isNotEmpty(subModelNames)) {
                            addModelSchema(tupleSchema, subModelNames, modelName);
                        }
                    }
                }
            } else {
                if(CollectionUtils.isNotEmpty(this.modelScoreNames)) {
                    addModelTagSchema(tupleSchema, this.modelScoreNames, "");
                    tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + "predict_tag", DataType.CHARARRAY));
                } else if(MapUtils.isEmpty(this.subModelScoreNames)) {
                    throw new IllegalStateException("No any model found!");
                }

                if(MapUtils.isNotEmpty(this.subModelScoreNames)) {
                    Iterator<Map.Entry<String, List<String>>> iterator = this.subModelScoreNames.entrySet().iterator();
                    while(iterator.hasNext()) {
                        Map.Entry<String, List<String>> entry = iterator.next();
                        String modelName = entry.getKey();
                        List<String> subScoreNames = entry.getValue();
                        if(CollectionUtils.isNotEmpty(subScoreNames)) {
                            addModelTagSchema(tupleSchema, subScoreNames, modelName);
                        }
                    }
                }
            }

            List<String> metaColumns = evalConfig.getAllMetaColumns(modelConfig);
            if(CollectionUtils.isNotEmpty(metaColumns)) {
                for(String columnName: metaColumns) {
                    tupleSchema.add(new FieldSchema(columnName, DataType.CHARARRAY));
                }
            }

            return new Schema(new Schema.FieldSchema("EvalScore", tupleSchema, DataType.TUPLE));
        } catch (IOException e) {
            log.error("Error in outputSchema", e);
            return null;
        }
    }

    /**
     * Add model(Regression) schema into tuple schema, if the modelCount > 0
     * 
     * @param tupleSchema
     *            - schema for Tuple
     * @param scoreNames
     *            - model score names
     * @param modelName
     *            - model name
     */
    private void addModelSchema(Schema tupleSchema, List<String> scoreNames, String modelName) {
        if(CollectionUtils.isNotEmpty(scoreNames)) {
            tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + addModelNameToField(modelName, "mean"), DataType.DOUBLE));
            tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + addModelNameToField(modelName, "max"), DataType.DOUBLE));
            tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + addModelNameToField(modelName, "min"), DataType.DOUBLE));
            tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + addModelNameToField(modelName, "median"), DataType.DOUBLE));
            for(int i = 0; i < scoreNames.size(); i++) {
                tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + addModelNameToField(modelName, scoreNames.get(i)),
                        DataType.DOUBLE));
            }
        }
    }

    /**
     * Add model(Classification) schema into tuple schema, if the modelCount > 0
     * 
     * @param tupleSchema
     *            - schema for Tuple
     * @param scoreNames
     *            - model score names
     * @param modelName
     *            - model name
     */
    private void addModelTagSchema(Schema tupleSchema, List<String> scoreNames, String modelName) {
        if(modelConfig.isClassification() && !modelConfig.getTrain().isOneVsAll()) {
            for(int i = 0; i < scoreNames.size(); i++) {
                for(int j = 0; j < modelConfig.getTags().size(); j++) {
                    tupleSchema.add(new FieldSchema(
                            SCHEMA_PREFIX + addModelNameToField(modelName, scoreNames.get(i) + "_tag_" + j),
                            DataType.DOUBLE));
                }
            }
        } else {
            // one vs all
            for(int i = 0; i < scoreNames.size(); i++) {
                tupleSchema.add(
                        new FieldSchema(SCHEMA_PREFIX + addModelNameToField(modelName, scoreNames.get(i) + "_tag_" + i),
                                DataType.DOUBLE));
            }
        }
    }

    /**
     * Add model(Regression) schema into tuple schema, if the modelCount > 0
     * 
     * @param tupleSchema
     *            - schema for Tuple
     * @param modelCount
     *            - model count
     * @param modelName
     *            - model name
     */
    private void addModelSchema(Schema tupleSchema, Integer modelCount, String modelName) {
        if(modelCount > 0) {
            tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + addModelNameToField(modelName, "mean"), DataType.DOUBLE));
            tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + addModelNameToField(modelName, "max"), DataType.DOUBLE));
            tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + addModelNameToField(modelName, "min"), DataType.DOUBLE));
            tupleSchema.add(new FieldSchema(SCHEMA_PREFIX + addModelNameToField(modelName, "median"), DataType.DOUBLE));
            for(int i = 0; i < modelCount; i++) {
                tupleSchema.add(
                        new FieldSchema(SCHEMA_PREFIX + addModelNameToField(modelName, "model" + i), DataType.DOUBLE));
            }
        }
    }

    /**
     * Add model name as the namespace of field
     * 
     * @param modelName
     *            - model name
     * @param field
     *            - field name
     * @return - tuple name with namespace
     */
    private String addModelNameToField(String modelName, String field) {
        return (StringUtils.isBlank(modelName) ? field : CommonUtils.normColumnName(modelName) + "::" + formatScoreField(field));
    }

    private String formatScoreField(String field) {
        return field.replaceAll("\\..*$", "");
    }

    private CategoryMissingNormType getCategoryMissingNormType() {
        CategoryMissingNormType categoryMissingNormType = null;
        if(UDFContext.getUDFContext() != null && UDFContext.getUDFContext().getJobConf() != null) {
            categoryMissingNormType = CategoryMissingNormType
                    .of(UDFContext.getUDFContext().getJobConf().get(Constants.SHIFU_NORM_CATEGORY_MISSING_NORM));
        } else {
            categoryMissingNormType = CategoryMissingNormType
                    .of(Environment.getProperty(Constants.SHIFU_NORM_CATEGORY_MISSING_NORM));
        }
        if(categoryMissingNormType == null) {
            categoryMissingNormType = CategoryMissingNormType.POSRATE;
        }
        log.info("'categoryMissingNormType' is set to: " + categoryMissingNormType);
        return categoryMissingNormType;
    }
}
