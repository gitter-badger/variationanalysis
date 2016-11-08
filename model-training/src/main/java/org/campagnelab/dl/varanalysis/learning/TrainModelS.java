package org.campagnelab.dl.varanalysis.learning;

import org.campagnelab.dl.model.utils.mappers.FeatureMapper;
import org.campagnelab.dl.model.utils.mappers.LabelMapper;
import org.campagnelab.dl.model.utils.mappers.SimpleFeatureCalculator;
import org.campagnelab.dl.varanalysis.learning.architecture.ComputationalGraphAssembler;
import org.campagnelab.dl.varanalysis.learning.architecture.graphs.SixDenseLayersNarrower2;
import org.campagnelab.dl.varanalysis.learning.iterators.MultiDataSetRecordIterator;
import org.campagnelab.dl.varanalysis.learning.iterators.SBIRecordIterator;
import org.campagnelab.dl.varanalysis.protobuf.BaseInformationRecords;
import org.campagnelab.goby.baseinfo.SequenceBaseInformationReader;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;
import java.util.function.Function;

/**
 * Train Somatic implemented with the Generic TrainModel
 */
public class TrainModelS extends TrainModel<BaseInformationRecords.BaseInformationOrBuilder> {
    static private Logger LOG = LoggerFactory.getLogger(TrainModelS.class);

    public static void main(String[] args) {

        TrainModelS tool = new TrainModelS();
        tool.parseArguments(args, "TrainModelS", tool.createArguments());
        if (tool.args().trainingSets.size() == 0) {
            System.out.println("Please add exactly one training set to the args().");
            return;
        }
        assert !tool.args().errorEnrichment : "This tool does not support error enrichment";
        tool.execute();
        tool.writeModelingConditions(tool.getRecordingArguments());
    }

    @Override
    public TrainingArguments createArguments() {
        return new SomaticTrainingArguments();
    }

    @Override
    protected DomainDescriptor<BaseInformationRecords.BaseInformationOrBuilder> domainDescriptor() {
        return new DomainDescriptor<BaseInformationRecords.BaseInformationOrBuilder>() {
            @Override
            public FeatureMapper getFeatureMapper(String inputName) {
                try {
                    featureMapper = TrainSomaticModel.configureFeatureMapper(args().featureMapperClassname, ((SomaticTrainingArguments) args()).isTrio,
                            args().getTrainingSets());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return featureMapper;
            }

            @Override
            public LabelMapper getLabelMapper(String outputName) {
                return new SimpleFeatureCalculator();
            }

            @Override
            public Function<String[], MultiDataSetRecordIterator<BaseInformationRecords.BaseInformationOrBuilder>> getIteratorFunction() {
                return trainingSetFilenames -> {
                    assert trainingSetFilenames.length == 1 : "This implementation only supports a single input file.";
                    try {
                        final int miniBatchSize = args().miniBatchSize;
                        return new SBIRecordIterator(trainingSetFilenames[0], miniBatchSize, domainDescriptor);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                };
            }

            @Override
            public ComputationalGraphAssembler getComputationalGraph() {
                return new SixDenseLayersNarrower2();
            }

            @Override
            public int[] getNumInputs(String inputName) {
                return new int[]{getFeatureMapper(inputName).numberOfFeatures()};
            }

            @Override
            public int[] getNumOutputs(String outputName) {
                return new int[]{getLabelMapper(outputName).numberOfLabels()};
            }

            @Override
            public int getNumHiddenNodes(String componentName) {
                return getNumInputs("input")[0] * 4;
            }

            @Override
            public LossFunctions.LossFunction getOutputLoss(String outputName) {
                switch (outputName) {
                    case "isMutated":
                        return LossFunctions.LossFunction.MCXENT;
                    case "somaticFrequency":
                        return LossFunctions.LossFunction.MSE;
                    default:
                        throw new IllegalArgumentException("Output name is not recognized");
                }
            }
        };
    }


    private static Properties getReaderProperties(String trainingSet) throws IOException {
        SequenceBaseInformationReader reader = new SequenceBaseInformationReader(trainingSet);
        final Properties properties = reader.getProperties();
        reader.close();
        return properties;
    }

}