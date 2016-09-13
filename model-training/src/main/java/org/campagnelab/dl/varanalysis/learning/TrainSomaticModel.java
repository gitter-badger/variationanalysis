package org.campagnelab.dl.varanalysis.learning;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.logging.ProgressLogger;
import org.campagnelab.dl.model.utils.mappers.FeatureMapperV18;
import org.campagnelab.dl.model.utils.mappers.trio.FeatureMapperV18Trio;
import org.campagnelab.dl.varanalysis.learning.models.ModelPropertiesHelper;
import org.campagnelab.dl.varanalysis.learning.models.ModelSaver;
import org.campagnelab.dl.varanalysis.util.ErrorRecord;
import org.campagnelab.dl.varanalysis.util.HitBoundedPriorityQueue;
import org.deeplearning4j.datasets.iterator.AsyncDataSetIterator;
import org.deeplearning4j.earlystopping.EarlyStoppingResult;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.PointIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Train a neural network to predict mutations.
 * <p>
 * Created by fac2003 on 5/21/16.
 *
 * @author Fabien Campagne
 */
public class TrainSomaticModel extends SomaticTrainer {
    public static final int MIN_ITERATION_BETWEEN_BEST_MODEL = 1000;
    static private Logger LOG = LoggerFactory.getLogger(TrainSomaticModel.class);

    private String validationDatasetFilename = null;


    /**
     * Error enrichment support.
     **/
    public static final int MAX_ERRORS_KEPT = 16;
    private final boolean ERROR_ENRICHMENT = false;
    private final int NUM_ERRORS_ADDED = 16;
    private final boolean IGNORE_ERRORS_ON_SIMULATED_EXAMPLES = false;
    private HitBoundedPriorityQueue queue = new HitBoundedPriorityQueue(MAX_ERRORS_KEPT);

    public TrainSomaticModel(TrainingArguments arguments) {
        super(arguments);
    }

    public static void main(String[] args) throws IOException {
        TrainingArguments arguments = parseArguments(args,"TrainSomaticModel");

        if (arguments.trainingSets.size() == 0){
            System.out.println("Please add at least one training set to the arguments.");
            return;
        }

        TrainSomaticModel trainer = new TrainSomaticModel(arguments);


        //for trio:
        if (arguments.isTrio){
            trainer.execute(new FeatureMapperV18Trio(), arguments.getTrainingSets(), arguments.miniBatchSize);
        } else {
            trainer.execute(new FeatureMapperV18(), arguments.getTrainingSets(), arguments.miniBatchSize);
        }
        //for duo
    }

    @Override
    protected EarlyStoppingResult<MultiLayerNetwork> train(MultiLayerConfiguration conf, DataSetIterator async) throws IOException {
        validationDatasetFilename = arguments.validationSet;
        //check validation file for error
        if (!(new File(validationDatasetFilename).exists())){
            throw new IOException("Validation file not found! "+validationDatasetFilename);
        }
        //Do training, and then generate and print samples from network
        int miniBatchNumber = 0;
        boolean init = true;
        ProgressLogger pgEpoch = new ProgressLogger(LOG);
        pgEpoch.itemsName = "epoch";
        pgEpoch.expectedUpdates = arguments.maxEpochs;
        pgEpoch.start();
        bestScore = Double.MAX_VALUE;
        ModelSaver saver = new ModelSaver(directory);
        int iter = 0;
        Map<Integer, Double> scoreMap = new HashMap<Integer, Double>();
        System.out.println("ERROR_ENRICHMENT=" + ERROR_ENRICHMENT);
        double bestAUC = 0.5;
        performanceLogger.setCondition(arguments.experimentalCondition);
        int numExamplesUsed=0;
        int notImproved=0;
        int miniBatchesPerEpoch = async.totalExamples() / arguments.miniBatchSize;
        System.out.printf("Training with %d minibatches per epoch%n",miniBatchesPerEpoch);
        for (int epoch = 0; epoch < arguments.maxEpochs; epoch++) {
            ProgressLogger pg = new ProgressLogger(LOG);
            pg.itemsName = "mini-batch";

            pg.expectedUpdates = miniBatchesPerEpoch; // one iteration processes miniBatchIterator elements.
            pg.start();
            int lastIter = 0;

            while (async.hasNext()) {

                DataSet ds = async.next();
                if (numLabels(ds.getLabels()) != 2) {
                    System.out.println("There should be two labels in the miniBatch");
                }

                ds = enrichWithErrors(ds);
                // fit the net:
                net.fit(ds);
                numExamplesUsed+=ds.numExamples();
                if (ERROR_ENRICHMENT) {
                    INDArray predictedLabels = net.output(ds.getFeatures(), false);
                    keepWorseErrors(ds, predictedLabels, ds.getLabels());
                }
                pg.lightUpdate();
                if (ERROR_ENRICHMENT && iter % 5 == 1) {
                    // update wrongness after 5 minibatches to give training a chance to learn how worse errors
                    // compare to the other records.
                    queue.updateWrongness(net);
                }
                double score = net.score();
                if (Double.isNaN(score)) {
                    //   System.out.println(net.params());
                    System.out.println("nan at " + iter);
                    System.out.println(ds.toString());
                    System.err.println("Aborting because NaN was generated for score.");
                    System.exit(1);
                }
                iter++;
                if (score < bestScore * 0.95 && iter > (lastIter + MIN_ITERATION_BETWEEN_BEST_MODEL)) {
                    bestScore = score;
                    saver.saveBestModel(net, score);
                    System.out.println("Saving best score model.. score=" + bestScore);
                    performanceLogger.log("best", numExamplesUsed, epoch, score);
                    lastIter = iter;

                }
                scoreMap.put(iter, bestScore);

            }
         //   System.err.println("Num Examples Used: "+numExamplesUsed);
            //save latest after the end of an epoch:
            saver.saveLatestModel(net, net.score());
            writeProperties(this);
            writeBestScoreFile();
            double auc = estimateTestSetPerf(epoch, iter);
            performanceLogger.log("epochs", numExamplesUsed, epoch, Double.NaN, auc);
            if (auc > bestAUC) {
                saver.saveModel(net, "bestAUC", auc);
                bestAUC = auc;
                writeBestAUC(bestAUC);
                performanceLogger.log("bestAUC", numExamplesUsed, epoch, bestScore, bestAUC);
                notImproved=0;
            }else {
                notImproved++;
            }
            if (notImproved>arguments.stopWhenEpochsWithoutImprovement) {
                // we have not improved after earlyStopCondition epoch, time to stop.
                break;
            }
            pg.stop();
            pgEpoch.update();
            queue.clear();
            async.reset();    //Reset iterator for another epoch
            performanceLogger.write();
        }
        pgEpoch.stop();

        return new EarlyStoppingResult<MultiLayerNetwork>(EarlyStoppingResult.TerminationReason.EpochTerminationCondition,
                "not early stopping", scoreMap, arguments.maxEpochs, bestScore, arguments.maxEpochs, net);
    }

    private void writeBestAUC(double bestAUC) {
        try {
            FileWriter scoreWriter = new FileWriter(directory + "/bestAUC");
            scoreWriter.append(Double.toString(bestAUC));
            scoreWriter.close();
        } catch (IOException e) {

        }

    }

    @Override
    public void appendProperties(ModelPropertiesHelper helper) {
        super.appendProperties(helper);
        helper.put("ErrorEnrichment.active", ERROR_ENRICHMENT);
        helper.put("ErrorEnrichment.MAX_ERRORS_KEPT", MAX_ERRORS_KEPT);
        helper.put("ErrorEnrichment.NUM_ERRORS_ADDED", NUM_ERRORS_ADDED);
        helper.put("ErrorEnrichment.IGNORE_ERRORS_ON_SIMULATED_EXAMPLES", IGNORE_ERRORS_ON_SIMULATED_EXAMPLES);
    }

    DataSet[] array = new DataSet[2];

    private DataSet enrichWithErrors(DataSet ds) {
        if (!ERROR_ENRICHMENT) {
            return ds;
        }
        if (queue.isEmpty()) {
            // no errors were collected yet. Return the un-enriched dataset.
            return ds;
        }

        int size = this.NUM_ERRORS_ADDED;
        INDArray inputs = Nd4j.zeros(size, featureCalculator.numberOfFeatures());
        INDArray labels = Nd4j.zeros(size, labelMapper.numberOfLabels());
        int i = 0;

        for (ErrorRecord errorRecord : queue.getRandomSample(size)) {
            // we are going to call nextRecord directly, without checking hasNextRecord, because we have
            // determined how many times we can call (in size). We should get the exception if we were
            // wrong in our estimate of size.

            // fill in features and labels for a given record i:
            Nd4j.copy(errorRecord.features.get(new PointIndex(0)), inputs.get(new PointIndex(i)));
            Nd4j.copy(errorRecord.label, labels.get(new PointIndex(i)));
            i++;

        }
        DataSet errorDataSet = new DataSet(inputs, labels);
        array[0] = ds;
        array[1] = errorDataSet;
        final DataSet enrichedDataset = DataSet.merge(ObjectArrayList.wrap(array));
        return enrichedDataset;
    }


    private void keepWorseErrors(DataSet minibatch, INDArray predictedLabels, INDArray labels) {
        if (!ERROR_ENRICHMENT) {
            return;
        }
        int size = minibatch.numExamples();
        for (int exampleIndex = 0; exampleIndex < size; exampleIndex++) {
            if (isWrongPrediction(exampleIndex, predictedLabels, labels)) {
                float wrongness = calculateWrongness(exampleIndex, predictedLabels, labels);
                queue.enqueue(wrongness, minibatch.getFeatures(), labels.getRow(exampleIndex));
                //    System.out.println("largest error so far: "+ queue.first());
            }
        }
    }

    private float calculateWrongness(int exampleIndex, INDArray predictedLabels, INDArray labels) {

        return ErrorRecord.calculateWrongness(exampleIndex, predictedLabels, labels);

    }

    private boolean isWrongPrediction(int exampleIndex, INDArray predictedLabels, INDArray labels) {
        if (!ERROR_ENRICHMENT) {
            return false;
        }
        if (IGNORE_ERRORS_ON_SIMULATED_EXAMPLES && labels.get(new PointIndex(exampleIndex)).getDouble(0) == 1) {
            //  do not consider errors on simulated/mutated examples. The simulator may be wrong.
            return false;
        }
        return ErrorRecord.isWrongPrediction(exampleIndex, predictedLabels, labels);

    }

    private double estimateTestSetPerf(int epoch, int iter) throws IOException {
        if (validationDatasetFilename == null) return 0;
        MeasurePerformance perf = new MeasurePerformance(arguments.numValidation);
        double auc = perf.estimateAUC(featureCalculator, net, validationDatasetFilename);
        float minWrongness = queue.getMinWrongness();
        float maxWrongness = queue.getMaxWrongness();
        float meanWrongness = queue.getMeanWrongness();

        System.out.printf("Epoch %d Iteration %d AUC=%f ", epoch, iter, auc);
        if (ERROR_ENRICHMENT) {
            System.out.printf("wrongness: %f-%f mean: %f #examples: %d %n",
                    minWrongness, maxWrongness, meanWrongness,
                    queue.size());
        } else {
            System.out.println();
        }
        return auc;
    }
}
