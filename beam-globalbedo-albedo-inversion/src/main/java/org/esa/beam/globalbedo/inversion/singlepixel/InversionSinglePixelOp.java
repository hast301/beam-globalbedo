package org.esa.beam.globalbedo.inversion.singlepixel;


import Jama.LUDecomposition;
import Jama.Matrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.pointop.*;
import org.esa.beam.globalbedo.inversion.Accumulator;
import org.esa.beam.globalbedo.inversion.AlbedoInversionConstants;
import org.esa.beam.globalbedo.inversion.FullAccumulator;
import org.esa.beam.globalbedo.inversion.Prior;
import org.esa.beam.globalbedo.inversion.util.AlbedoInversionUtils;
import org.esa.beam.globalbedo.inversion.util.IOUtils;

import static java.lang.Math.*;
import static org.esa.beam.globalbedo.inversion.AlbedoInversionConstants.*;

/**
 * Pixel operator for inversion of a single pixel
 *
 * @author Olaf Danne
 */
@OperatorMetadata(alias = "ga.inversion.inversion",
        description = "Pixel operator for inversion of a single pixel",
        authors = "Olaf Danne",
        version = "1.0",
        copyright = "(C) 2016 by Brockmann Consult")
public class InversionSinglePixelOp extends PixelOperator {

    public static final int[][] SRC_PRIOR_MEAN = new int[NUM_ALBEDO_PARAMETERS][NUM_ALBEDO_PARAMETERS];

    public static final int[][] SRC_PRIOR_SD = new int[NUM_ALBEDO_PARAMETERS][NUM_ALBEDO_PARAMETERS];

    public static final int SOURCE_SAMPLE_OFFSET = 0;  // this value must be >= number of bands in a source product
    public static final int PRIOR_OFFSET = (int) pow(NUM_ALBEDO_PARAMETERS, 2.0);
    public static final int SRC_PRIOR_NSAMPLES = 2 * PRIOR_OFFSET;

    public static final int SRC_PRIOR_MASK = SOURCE_SAMPLE_OFFSET + 2 * PRIOR_OFFSET + 1;

    private static final int NUM_TRG_PARAMETERS = 3 * NUM_BBDR_WAVE_BANDS;

    // this offset is the number of UR matrix elements + diagonale. Should be 45 for 9x9 matrix...
    private static final int NUM_TRG_UNCERTAINTIES = ((int) pow(3 * NUM_BBDR_WAVE_BANDS, 2.0) + 3 * NUM_BBDR_WAVE_BANDS) / 2;

    private static final int TRG_REL_ENTROPY = 1;
    private static final int TRG_WEIGHTED_NUM_SAMPLES = 2;
    private static final int TRG_GOODNESS_OF_FIT = 3;
    private static final int TRG_DAYS_TO_THE_CLOSEST_SAMPLE = 4;
    private static final int TRG_LAT = 5;
    private static final int TRG_LON = 6;

    private static final String[] PARAMETER_BAND_NAMES = IOUtils.getInversionParameterBandNames();
    private static final String[][] UNCERTAINTY_BAND_NAMES = IOUtils.getInversionUncertaintyBandNames();

    static {
        for (int i = 0; i < NUM_ALBEDO_PARAMETERS; i++) {
            for (int j = 0; j < NUM_ALBEDO_PARAMETERS; j++) {
                SRC_PRIOR_MEAN[i][j] = SOURCE_SAMPLE_OFFSET + NUM_ALBEDO_PARAMETERS * i + j;
                SRC_PRIOR_SD[i][j] = SOURCE_SAMPLE_OFFSET + PRIOR_OFFSET + NUM_ALBEDO_PARAMETERS * i + j;
            }
        }
    }

    @SourceProduct(description = "Prior product", optional = true)
    private Product priorProduct;

    @Parameter(description = "Year")
    private int year;

    @Parameter(description = "Tile")
    private String tile;

    @Parameter(description = "Day of year")
    private int doy;

    @Parameter(description = "Latitude")
    private float latitude;

    @Parameter(description = "Latitude")
    private float longitude;

    @Parameter(description = "Full accumulator")
    private FullAccumulator fullAccumulator;

    @Parameter(defaultValue = "false", description = "Compute only snow pixels")
    private boolean computeSnow;

    @Parameter(defaultValue = "true", description = "Use prior information")
    private boolean usePrior;

    @Parameter(defaultValue = "30.0", description = "Prior scale factor")
    private double priorScaleFactor;

//    @Parameter(defaultValue = "MEAN:_BAND_", description = "Prefix of prior mean band (default fits to the latest prior version)")
    // Oct. 2015:
    @Parameter(defaultValue = "Mean_", description = "Prefix of prior mean band (default fits to the latest prior version)")
    private String priorMeanBandNamePrefix;

//    @Parameter(defaultValue = "SD:_BAND_", description = "Prefix of prior SD band (default fits to the latest prior version)")
    @Parameter(defaultValue = "Cov_", description = "Prefix of prior SD band (default fits to the latest prior version)")
    private String priorSdBandNamePrefix;

    @Parameter(defaultValue = "7", description = "Prior broad bands start index (default fits to the latest prior version)")
    private int priorBandStartIndex;

    @Parameter(defaultValue = "Weighted_number_of_samples", description = "Prior NSamples band name (default fits to the latest prior version)")
    private String priorNSamplesBandName;

//    @Parameter(defaultValue = "land_mask", description = "Prior NSamples band name (default fits to the latest prior version)")
    @Parameter(defaultValue = "Data_Mask", description = "Prior NSamples band name (default fits to the latest prior version)")
    private String priorLandMaskBandName;

    Accumulator[][] singlePixelAccumulator;

    @Override
    protected void prepareInputs() throws OperatorException {
        super.prepareInputs();
        singlePixelAccumulator = new Accumulator[1][1];
    }

    @Override
    protected void configureTargetProduct(ProductConfigurer productConfigurer) {
        super.configureTargetProduct(productConfigurer);

        for (String parameterBandName : PARAMETER_BAND_NAMES) {
            productConfigurer.addBand(parameterBandName, ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
        }

        for (int i = 0; i < 3 * NUM_BBDR_WAVE_BANDS; i++) {
            // add bands only for UR triangular matrix
            for (int j = i; j < 3 * NUM_BBDR_WAVE_BANDS; j++) {
                productConfigurer.addBand(UNCERTAINTY_BAND_NAMES[i][j], ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
            }
        }

        productConfigurer.addBand(INV_ENTROPY_BAND_NAME, ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
        productConfigurer.addBand(INV_REL_ENTROPY_BAND_NAME, ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
        productConfigurer.addBand(INV_WEIGHTED_NUMBER_OF_SAMPLES_BAND_NAME, ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
        productConfigurer.addBand(ACC_DAYS_TO_THE_CLOSEST_SAMPLE_BAND_NAME, ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
        productConfigurer.addBand(INV_GOODNESS_OF_FIT_BAND_NAME, ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
        productConfigurer.addBand(LAT_BAND_NAME, ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
        productConfigurer.addBand(LON_BAND_NAME, ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
    }

    @Override
    protected void configureSourceSamples(SampleConfigurer configurator) {

        // prior product:
        // we have:
        // 3x3 mean, 3x3 SD, Nsamples, mask
        if (usePrior) {
            for (int i = 0; i < NUM_ALBEDO_PARAMETERS; i++) {
                for (int j = 0; j < NUM_ALBEDO_PARAMETERS; j++) {
                    final String indexString = Integer.toString(priorBandStartIndex + i);
//                    final String meanBandName = "MEAN__BAND________" + i + "_PARAMETER_F" + j;
                    // 2014, e.g. MEAN:_BAND_7_PARAMETER_F1
//                    final String meanBandName = priorMeanBandNamePrefix + indexString + "_PARAMETER_F" + j;
                    // Oct. 2015 version, e.g. Mean_VIS_f0
                    final String meanBandName = priorMeanBandNamePrefix + IOUtils.waveBandsOffsetMap.get(i / 3) + "_f" + j;
                    configurator.defineSample(SRC_PRIOR_MEAN[i][j], meanBandName, priorProduct);

//                    final String sdMeanBandName = "SD_MEAN__BAND________" + i + "_PARAMETER_F" + j;
                    // 2014, e.g. SD:_BAND_7_PARAMETER_F1
//                    final String sdMeanBandName = priorSdBandNamePrefix + indexString + "_PARAMETER_F" + j;
                    // Oct. 2015 version:
                    // SD:_BAND_7_PARAMETER_F0 --> now Cov_VIS_f0_VIS_f0
                    // SD:_BAND_7_PARAMETER_F1 --> now Cov_VIS_f1_VIS_f1
                    // SD:_BAND_7_PARAMETER_F2 --> now Cov_VIS_f2_VIS_f2
                    // SD:_BAND_8_PARAMETER_F0 --> now Cov_NIR_f0_NIR_f0
                    // SD:_BAND_8_PARAMETER_F1 --> now Cov_NIR_f1_NIR_f1
                    // SD:_BAND_8_PARAMETER_F2 --> now Cov_NIR_f2_NIR_f2
                    // SD:_BAND_9_PARAMETER_F0 --> now Cov_SW_f0_SW_f0
                    // SD:_BAND_9_PARAMETER_F1 --> now Cov_SW_f1_SW_f1
                    // SD:_BAND_9_PARAMETER_F2 --> now Cov_SW_f2_SW_f2
                    final String sdMeanBandName = priorSdBandNamePrefix +
                            IOUtils.waveBandsOffsetMap.get(i / 3) + "_f" + j + "_" +
                            IOUtils.waveBandsOffsetMap.get(i / 3) + "_f" + j;
                    configurator.defineSample(SRC_PRIOR_SD[i][j], sdMeanBandName, priorProduct);
                }
            }
//            configurator.defineSample(SRC_PRIOR_NSAMPLES, PRIOR_NSAMPLES_NAME, priorProduct);
            configurator.defineSample(SRC_PRIOR_NSAMPLES, priorNSamplesBandName, priorProduct);
//            configurator.defineSample(SRC_PRIOR_MASK, PRIOR_MASK_NAME, priorProduct);
            configurator.defineSample(SRC_PRIOR_MASK, priorLandMaskBandName, priorProduct);
        }
    }

    @Override
    protected void configureTargetSamples(SampleConfigurer configurator) {

        for (int i = 0; i < 3 * NUM_BBDR_WAVE_BANDS; i++) {
            configurator.defineSample(i, PARAMETER_BAND_NAMES[i]);
        }

        int index = 0;
        for (int i = 0; i < 3 * NUM_BBDR_WAVE_BANDS; i++) {
            for (int j = i; j < 3 * NUM_ALBEDO_PARAMETERS; j++) {
                configurator.defineSample(NUM_TRG_PARAMETERS + index, UNCERTAINTY_BAND_NAMES[i][j]);
                index++;
            }
        }

        int offset = NUM_TRG_PARAMETERS + NUM_TRG_UNCERTAINTIES;
        configurator.defineSample(offset, INV_ENTROPY_BAND_NAME);
        configurator.defineSample(offset + TRG_REL_ENTROPY, INV_REL_ENTROPY_BAND_NAME);
        configurator.defineSample(offset + TRG_WEIGHTED_NUM_SAMPLES, INV_WEIGHTED_NUMBER_OF_SAMPLES_BAND_NAME);
        configurator.defineSample(offset + TRG_GOODNESS_OF_FIT, INV_GOODNESS_OF_FIT_BAND_NAME);
        configurator.defineSample(offset + TRG_DAYS_TO_THE_CLOSEST_SAMPLE, ACC_DAYS_TO_THE_CLOSEST_SAMPLE_BAND_NAME);
        configurator.defineSample(offset + TRG_LAT, LAT_BAND_NAME);
        configurator.defineSample(offset + TRG_LON, LON_BAND_NAME);
    }

    @Override
    protected void computePixel(int x, int y, Sample[] sourceSamples, WritableSample[] targetSamples) {
        // we have only one pixel to process!!

        Matrix parameters = new Matrix(NUM_BBDR_WAVE_BANDS * NUM_ALBEDO_PARAMETERS, 1, AlbedoInversionConstants.NO_DATA_VALUE);
        Matrix uncertainties;

        double entropy = 0.0; // == det in BB
        double relEntropy = 0.0;

        double maskAcc = 0.0;
        if (fullAccumulator != null) {
            singlePixelAccumulator[0][0] = fullAccumulator.getAccumulator()[0][0];
            maskAcc = singlePixelAccumulator[0][0].getMask();
        }

        double maskPrior = 1.0;
        Prior prior = null;
        if (usePrior) {
            prior = Prior.createForInversion(sourceSamples, priorScaleFactor);
            maskPrior = prior.getMask();
        }

        double goodnessOfFit = 0.0;
        float daysToTheClosestSample = 0.0f;
        if (singlePixelAccumulator[0][0] != null && maskAcc > 0 && ((usePrior && maskPrior > 0) || !usePrior)) {
            final Matrix mAcc = singlePixelAccumulator[0][0].getM();
            Matrix vAcc = singlePixelAccumulator[0][0].getV();
            final Matrix eAcc = singlePixelAccumulator[0][0].getE();
//            final Matrix mAcc = AlbedoInversionUtils.getMatrix2DTruncated(singlePixelAccumulator[0][0].getM());
//            Matrix vAcc = AlbedoInversionUtils.getMatrix2DTruncated(singlePixelAccumulator[0][0].getV());
//            final Matrix eAcc = AlbedoInversionUtils.getMatrix2DTruncated(singlePixelAccumulator[0][0].getE());
//            mAcc.set(2,2, 5955.84); // test!!!

            if (usePrior && prior != null) {
                for (int i = 0; i < 3 * NUM_BBDR_WAVE_BANDS; i++) {
                    double m_ii_accum = mAcc.get(i, i);
                    if (prior.getM() != null) {
                        m_ii_accum += prior.getM().get(i, i);
                    }
                    mAcc.set(i, i, m_ii_accum);
                }
                vAcc = vAcc.plus(prior.getV());
            }

            final LUDecomposition lud = new LUDecomposition(mAcc);
            if (lud.isNonsingular()) {
                Matrix tmpM = mAcc.inverse();
                if (AlbedoInversionUtils.matrixHasNanElements(tmpM) ||
                        AlbedoInversionUtils.matrixHasZerosInDiagonale(tmpM)) {
                    tmpM = new Matrix(3 * NUM_BBDR_WAVE_BANDS,
                                      3 * NUM_ALBEDO_PARAMETERS,
                                      AlbedoInversionConstants.NO_DATA_VALUE);
                }
                uncertainties = tmpM;
            } else {
                parameters = new Matrix(NUM_BBDR_WAVE_BANDS *
                                                NUM_ALBEDO_PARAMETERS, 1,
                                        AlbedoInversionConstants.NO_DATA_VALUE
                );
                uncertainties = new Matrix(3 * NUM_BBDR_WAVE_BANDS,
                                           3 * NUM_ALBEDO_PARAMETERS,
                                           AlbedoInversionConstants.NO_DATA_VALUE);
                maskAcc = 0.0;
            }

            if (maskAcc != 0.0) {
                // todo: we get differences from standard processing as we use here double precision throughout
                // daily acc --> full acc
                // test here with matrix elements casted down to float and then back to double
                // vAccNew := (double) ((float) vAcc.get(0,0)) etc.
                parameters = mAcc.solve(vAcc);
                entropy = getEntropy(mAcc);
                if (usePrior && prior != null && prior.getM() != null) {
                    final double entropyPrior = getEntropy(prior.getM());
                    relEntropy = entropyPrior - entropy;
                } else {
                    relEntropy = AlbedoInversionConstants.NO_DATA_VALUE;
                }
            }
            // 'Goodness of Fit'...
            goodnessOfFit = getGoodnessOfFit(mAcc, vAcc, eAcc, parameters, maskAcc);

            // finally we need the 'Days to the closest sample'...
            daysToTheClosestSample = fullAccumulator.getDaysToTheClosestSample()[x][y];
        } else {
            if (maskPrior > 0.0) {
                if (usePrior) {
                    parameters = prior.getParameters();
                    final LUDecomposition lud = new LUDecomposition(prior.getM());
                    if (lud.isNonsingular()) {
                        uncertainties = prior.getM().inverse();
                        entropy = getEntropy(prior.getM());
                    } else {
                        uncertainties = new Matrix(
                                3 * NUM_BBDR_WAVE_BANDS,
                                3 * NUM_ALBEDO_PARAMETERS, AlbedoInversionConstants.NO_DATA_VALUE);
                        entropy = AlbedoInversionConstants.NO_DATA_VALUE;
                    }
                    relEntropy = 0.0;
                } else {
                    uncertainties = new Matrix(
                            3 * NUM_BBDR_WAVE_BANDS,
                            3 * NUM_ALBEDO_PARAMETERS, AlbedoInversionConstants.NO_DATA_VALUE);
                    entropy = AlbedoInversionConstants.NO_DATA_VALUE;
                    relEntropy = AlbedoInversionConstants.NO_DATA_VALUE;
                }
            } else {
                uncertainties = new Matrix(
                        3 * NUM_BBDR_WAVE_BANDS,
                        3 * NUM_ALBEDO_PARAMETERS, AlbedoInversionConstants.NO_DATA_VALUE);
                entropy = AlbedoInversionConstants.NO_DATA_VALUE;
                relEntropy = AlbedoInversionConstants.NO_DATA_VALUE;
            }
        }

        // we have the final result - fill target samples...
        fillTargetSamples(targetSamples,
                          parameters, uncertainties, entropy, relEntropy,
                          maskAcc, goodnessOfFit, daysToTheClosestSample);
    }

    private double getGoodnessOfFit(Matrix mAcc, Matrix vAcc, Matrix eAcc, Matrix fPars, double maskAcc) {
        Matrix goodnessOfFitMatrix = new Matrix(1, 1, 0.0);
        if (maskAcc > 0) {
            final Matrix gofTerm1 = fPars.transpose().times(mAcc).times(fPars);
            final Matrix gofTerm2 = fPars.transpose().times(vAcc);
            final Matrix m2 = new Matrix(1, 1, 2.0);
            final Matrix gofTerm3 = m2.times(eAcc);
            goodnessOfFitMatrix = gofTerm1.plus(gofTerm2).minus(gofTerm3);
        }
        return goodnessOfFitMatrix.get(0, 0);
    }

    private void fillTargetSamples(WritableSample[] targetSamples,
                                   Matrix parameters, Matrix uncertainties, double entropy, double relEntropy,
                                   double weightedNumberOfSamples, double goodnessOfFit, float daysToTheClosestSample) {

        // parameters
        int index = 0;
        for (int i = 0; i < NUM_BBDR_WAVE_BANDS; i++) {
            for (int j = 0; j < NUM_BBDR_WAVE_BANDS; j++) {
                targetSamples[index].set(parameters.get(index, 0));
                index++;
            }
        }

        for (int i = 0; i < 3 * NUM_BBDR_WAVE_BANDS; i++) {
            for (int j = i; j < 3 * NUM_BBDR_WAVE_BANDS; j++) {
                targetSamples[index].set(uncertainties.get(i, j));
                index++;
            }
        }

        int offset = NUM_TRG_PARAMETERS + NUM_TRG_UNCERTAINTIES;
        targetSamples[offset].set(entropy);
        targetSamples[offset + TRG_REL_ENTROPY].set(relEntropy);
        targetSamples[offset + TRG_WEIGHTED_NUM_SAMPLES].set(weightedNumberOfSamples);
        targetSamples[offset + TRG_GOODNESS_OF_FIT].set(goodnessOfFit);
        targetSamples[offset + TRG_DAYS_TO_THE_CLOSEST_SAMPLE].set(daysToTheClosestSample);
        targetSamples[offset + TRG_LAT].set(latitude);
        targetSamples[offset + TRG_LON].set(longitude);

    }

    private double getEntropy(Matrix m) {
        // final SingularValueDecomposition svdM = m.svd();     // this sometimes gets stuck at CEMS!!
        //  --> single value decomposition from apache.commons.math3 seems to do better
        final RealMatrix rm = AlbedoInversionUtils.getRealMatrixFromJamaMatrix(m);
        final SingularValueDecomposition svdM = new SingularValueDecomposition(rm);
        final double[] svdMSingularValues = svdM.getSingularValues();
        // see python BB equivalent at http://nullege.com/codes/search/numpy.prod
        double productSvdMSRecip = 1.0;
        for (double svdMSingularValue : svdMSingularValues) {
            if (svdMSingularValue != 0.0) {
                productSvdMSRecip *= (1.0 / svdMSingularValue);
            }
        }
        return 0.5 * log(productSvdMSRecip) + svdMSingularValues.length * sqrt(log(2.0 * PI * E));
    }


    public static class Spi extends OperatorSpi {

        public Spi() {
            super(InversionSinglePixelOp.class);
        }
    }
}
