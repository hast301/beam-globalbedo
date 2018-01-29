package org.esa.beam.globalbedo.inversion.spectral;

import Jama.LUDecomposition;
import Jama.Matrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.pointop.*;
import org.esa.beam.globalbedo.inversion.AlbedoInversionConstants;
import org.esa.beam.globalbedo.inversion.FullAccumulator;
import org.esa.beam.globalbedo.inversion.util.AlbedoInversionUtils;

import java.util.HashMap;
import java.util.Map;

import static java.lang.Math.*;
import static org.esa.beam.globalbedo.inversion.AlbedoInversionConstants.NUM_ALBEDO_PARAMETERS;

/**
 * Pixel operator implementing the inversion part extended from broadband to spectral appropach (MODIS bands).
 *
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
@OperatorMetadata(alias = "ga.inversion.inversion.spectral.withpriors",
        description = "Implements the inversion part extended from broadband to spectral appropach (MODIS bands).",
        authors = "Olaf Danne",
        version = "1.0",
        copyright = "(C) 2016 by Brockmann Consult")

public class SpectralInversionWithPriorsOp extends PixelOperator {

    @SourceProduct(description = "Source product", optional = true)
    private Product sourceProduct;

    @SourceProduct(description = "Prior product")
    private Product priorProduct;

    @Parameter(description = "Year")
    private int year;

    @Parameter(description = "Tile")
    private String tile;

    @Parameter(description = "Day of year")
    private int doy;

    @Parameter(defaultValue = "180", description = "Wings")  // means 3 months wings on each side of the year
    private int wings;

    @Parameter(defaultValue = "", description = "Daily acc binary files root directory")
    private String dailyAccRootDir;

    @Parameter(defaultValue = "false", description = "Compute only snow pixels")
    private boolean computeSnow;

    @Parameter(defaultValue = "3", interval = "[1,7]", description = "Band index in case only 1 SDR band is processed")
    private int singleBandIndex;    // todo: consider chemistry bands

    private String priorMeanBandNamePrefix = "BRDF_Albedo_Parameters_";
    private String priorSdBandNamePrefix = "BRDF_Albedo_Parameters_";


    private double priorScaleFactor = 30.0;

    private static final int TRG_REL_ENTROPY = 1;
    private static final int TRG_WEIGHTED_NUM_SAMPLES = 2;
    private static final int TRG_GOODNESS_OF_FIT = 3;
    private static final int TRG_DAYS_TO_THE_CLOSEST_SAMPLE = 4;

    private String[] parameterBandNames;
    private String[][] uncertaintyBandNames;

    private int numTargetParameters;
    private int numTargetUncertainties;

    static final Map<Integer, String> spectralWaveBandsMap = new HashMap<>();

    private FullAccumulator fullAccumulator;

    private int numSdrBands = 1;     // bands to process
    private int totalNumSdrBands = 7;     // all MODIS bands

    @Override
    protected void prepareInputs() throws OperatorException {
        super.prepareInputs();

        spectralWaveBandsMap.put(0, "b" + (singleBandIndex));
        parameterBandNames = SpectralIOUtils.getSpectralInversionParameterSingleBandNames(singleBandIndex);
        uncertaintyBandNames = SpectralIOUtils.getSpectralInversionUncertaintySingleBandNames(spectralWaveBandsMap);

        numTargetParameters = 3 * numSdrBands;
//        numTargetUncertainties = ((int) pow(3 * numSdrBands, 2.0) + 3 * numSdrBands) / 2;
        // Nov. 2016: only provide the SD (diagonal terms), not the full matrix!
        numTargetUncertainties = 3 * numSdrBands;

        SpectralFullAccumulation fullAccumulation = new SpectralFullAccumulation(numSdrBands,
                                                                                 dailyAccRootDir,
                                                                                 singleBandIndex,
                                                                                 tile, year, doy,
                                                                                 wings, computeSnow);
        fullAccumulator = fullAccumulation.getResult();
    }

    @Override
    protected void computePixel(int x, int y, Sample[] sourceSamples, WritableSample[] targetSamples) {
        Matrix parameters = new Matrix(numSdrBands * AlbedoInversionConstants.NUM_ALBEDO_PARAMETERS, 1,
                                       AlbedoInversionConstants.NO_DATA_VALUE);
        Matrix uncertainties;

        double entropy = 0.0; // == det in BB
        double relEntropy = AlbedoInversionConstants.NO_DATA_VALUE;

        if (x == 200 && y == 200) {
            System.out.println("x = " + x);
        }

        double maskAcc = 0.0;
        SpectralAccumulator accumulator = null;
        if (fullAccumulator != null) {
            accumulator = SpectralAccumulator.createForInversion(fullAccumulator.getSumMatrices(), x, y, numSdrBands);
            maskAcc = accumulator.getMask();
        }

        double goodnessOfFit = 0.0;
        float daysToTheClosestSample = 0.0f;

        double maskPrior = 1.0;
        double priorValidPixelFlag = 0;
        SpectralPrior prior = SpectralPrior.createForInversion(sourceSamples, priorScaleFactor, computeSnow, singleBandIndex);
        maskPrior = prior.getMask();
        priorValidPixelFlag = prior.getPriorValidPixelFlag();

        if (accumulator != null && maskAcc > 0 && maskPrior > 0) {
            final Matrix mAcc = accumulator.getM();
            Matrix vAcc = accumulator.getV();
            final Matrix eAcc = accumulator.getE();

            for (int i = 0; i < 3 * numSdrBands; i++) {
                double m_ii_accum = mAcc.get(i, i);
                if (prior.getM() != null) {
                    m_ii_accum += prior.getM().get(i, i);
                }
                mAcc.set(i, i, m_ii_accum);
            }
            for (int i = 0; i < 3 * numSdrBands; i++) {
                double v_i_accum = vAcc.get(i, 0);
                if (prior.getV() != null) {
                    v_i_accum += prior.getV().get(i, 0);
                }
                vAcc.set(i, 0, v_i_accum);
            }

            final LUDecomposition lud = new LUDecomposition(mAcc);
            if (lud.isNonsingular()) {
                Matrix tmpM = mAcc.inverse();   // 3x9
                if (AlbedoInversionUtils.matrixHasNanElements(tmpM) ||
                        AlbedoInversionUtils.matrixHasZerosInDiagonale(tmpM)) {
                    tmpM = new Matrix(3 * numSdrBands, 3 * AlbedoInversionConstants.NUM_ALBEDO_PARAMETERS,
                                      AlbedoInversionConstants.NO_DATA_VALUE);
                }
                uncertainties = tmpM;
            } else {
                parameters = new Matrix(numSdrBands * AlbedoInversionConstants.NUM_ALBEDO_PARAMETERS, 1,
                                        AlbedoInversionConstants.NO_DATA_VALUE);        // 1x3
                uncertainties = new Matrix(3 * numSdrBands,
                                           3 * numSdrBands,
                                           AlbedoInversionConstants.NO_DATA_VALUE);     // 3x3
                maskAcc = 0.0;
            }

            if (maskAcc != 0.0) {
                parameters = mAcc.solve(vAcc);
                entropy = getEntropy(mAcc);
                if (prior.getM() != null) {
                    final double entropyPrior = getEntropy(prior.getM());
                    relEntropy = entropyPrior - entropy;
                }
            }
            // 'Goodness of Fit'...
            goodnessOfFit = getGoodnessOfFit(mAcc, vAcc, eAcc, parameters, maskAcc);
//            relEntropy = AlbedoInversionConstants.NO_DATA_VALUE; // without MODIS priors we have no relative entropy

            // finally we need the 'Days to the closest sample'...
            daysToTheClosestSample = fullAccumulator.getDaysToTheClosestSample()[x][y];
        } else {
            if (maskPrior > 0.0) {
                parameters = prior.getParameters();
                final LUDecomposition lud = new LUDecomposition(prior.getM());
                if (lud.isNonsingular()) {
                    uncertainties = prior.getM().inverse();
                    entropy = getEntropy(prior.getM());
                } else {
                    uncertainties = new Matrix(
                            3 * numSdrBands,
                            3 * numSdrBands, AlbedoInversionConstants.NO_DATA_VALUE);
                    entropy = AlbedoInversionConstants.NO_DATA_VALUE;
                }
                relEntropy = 0.0;
            } else {
                uncertainties = new Matrix(3 * numSdrBands, 3 * numSdrBands,
                                           AlbedoInversionConstants.NO_DATA_VALUE);
                entropy = AlbedoInversionConstants.NO_DATA_VALUE;
                relEntropy = AlbedoInversionConstants.NO_DATA_VALUE;
            }
        }

        // we have the final result - fill target samples...
        fillTargetSamples(targetSamples,
                          parameters, uncertainties, entropy, relEntropy,
                          maskAcc, goodnessOfFit, daysToTheClosestSample, priorValidPixelFlag);
    }

    @Override
    protected void configureSourceSamples(SampleConfigurer sampleConfigurer) throws OperatorException {
        configurePriorSourceSamples(sampleConfigurer);
    }

    @Override
    protected void configureTargetSamples(SampleConfigurer configurator) throws OperatorException {
        for (int i = 0; i < 3 * numSdrBands; i++) {
            configurator.defineSample(i, parameterBandNames[i]);
        }

        int index = 0;
        for (int i = 0; i < 3 * numSdrBands; i++) {
//            for (int j = i; j < 3 * numSdrBands; j++) {
//                configurator.defineSample(numTargetParameters + index, uncertaintyBandNames[i][j]);
//                index++;
//            }
            // Nov. 2016: only provide the SD (diagonal terms), not the full matrix!
            configurator.defineSample(numTargetParameters + index, uncertaintyBandNames[i][i]);
            index++;
        }

        int offset = numTargetParameters + numTargetUncertainties;
        configurator.defineSample(offset, AlbedoInversionConstants.INV_ENTROPY_BAND_NAME);
        configurator.defineSample(offset + TRG_REL_ENTROPY, AlbedoInversionConstants.INV_REL_ENTROPY_BAND_NAME);
        configurator.defineSample(offset + TRG_WEIGHTED_NUM_SAMPLES, AlbedoInversionConstants.INV_WEIGHTED_NUMBER_OF_SAMPLES_BAND_NAME);
        configurator.defineSample(offset + TRG_GOODNESS_OF_FIT, AlbedoInversionConstants.INV_GOODNESS_OF_FIT_BAND_NAME);
        configurator.defineSample(offset + TRG_DAYS_TO_THE_CLOSEST_SAMPLE, AlbedoInversionConstants.ACC_DAYS_TO_THE_CLOSEST_SAMPLE_BAND_NAME);
    }

    @Override
    protected void configureTargetProduct(ProductConfigurer productConfigurer) {
        super.configureTargetProduct(productConfigurer);

        for (String parameterBandName : parameterBandNames) {
            productConfigurer.addBand(parameterBandName, ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
        }

        for (int i = 0; i < 3 * numSdrBands; i++) {
            // add bands only for UR triangular matrix
//            for (int j = i; j < 3 * numSdrBands; j++) {
//                productConfigurer.addBand(uncertaintyBandNames[i][j], ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
//            }
            // Nov. 2016: only provide the SD (diagonal terms), not the full matrix!
            productConfigurer.addBand(uncertaintyBandNames[i][i], ProductData.TYPE_FLOAT32, AlbedoInversionConstants.NO_DATA_VALUE);
        }

        productConfigurer.addBand(AlbedoInversionConstants.INV_ENTROPY_BAND_NAME, ProductData.TYPE_FLOAT32,
                                  AlbedoInversionConstants.NO_DATA_VALUE);
        productConfigurer.addBand(AlbedoInversionConstants.INV_REL_ENTROPY_BAND_NAME, ProductData.TYPE_FLOAT32,
                                  AlbedoInversionConstants.NO_DATA_VALUE);
        productConfigurer.addBand(AlbedoInversionConstants.INV_WEIGHTED_NUMBER_OF_SAMPLES_BAND_NAME, ProductData.TYPE_FLOAT32,
                                  AlbedoInversionConstants.NO_DATA_VALUE);
        productConfigurer.addBand(AlbedoInversionConstants.ACC_DAYS_TO_THE_CLOSEST_SAMPLE_BAND_NAME, ProductData.TYPE_FLOAT32,
                                  AlbedoInversionConstants.NO_DATA_VALUE);
        productConfigurer.addBand(AlbedoInversionConstants.INV_GOODNESS_OF_FIT_BAND_NAME, ProductData.TYPE_FLOAT32,
                                  AlbedoInversionConstants.NO_DATA_VALUE);

        for (Band b : getTargetProduct().getBands()) {
            b.setNoDataValue(AlbedoInversionConstants.NO_DATA_VALUE);
            b.setNoDataValueUsed(true);
        }
    }

    static void setupSpectralWaveBandsMap(int numSdrBands) {
        for (int i = 0; i < numSdrBands; i++) {
            spectralWaveBandsMap.put(i, "b" + (i + 1));
        }
    }

    private double getGoodnessOfFit(Matrix mAcc, Matrix vAcc, Matrix eAcc, Matrix fPars, double maskAcc) {
        Matrix goodnessOfFitMatrix = new Matrix(1, 1, 0.0);
        if (maskAcc > 0) {
            final Matrix gofTerm1 = fPars.transpose().times(mAcc).times(fPars);
            final Matrix gofTerm2 = fPars.transpose().times(vAcc);         // fpars = mAcc.solve(vAcc) ?!
//            final Matrix gofTerm2 = fPars.times(vAcc.transpose());         // like in breadboard but wrong?!
            final Matrix m2 = new Matrix(1, 1, 2.0);
            final Matrix gofTerm3 = m2.times(eAcc);
            goodnessOfFitMatrix = gofTerm1.plus(gofTerm2).minus(gofTerm3);
        }
        return goodnessOfFitMatrix.get(0, 0);
    }

    private void fillTargetSamples(WritableSample[] targetSamples,
                                   Matrix parameters, Matrix uncertainties, double entropy, double relEntropy,
                                   double weightedNumberOfSamples,
                                   double goodnessOfFit,
                                   float daysToTheClosestSample,
                                   double priorValidPixelFlag) {

        // parameters
        int index = 0;
        for (int i = 0; i < 3 * numSdrBands; i++) {
            targetSamples[index].set(parameters.get(index, 0));
            index++;
        }

        // uncertainties
        index = 0;
        for (int i = 0; i < 3 * numSdrBands; i++) {
//            for (int j = i; j < 3 * numSdrBands; j++) {
//                targetSamples[numTargetParameters + index].set(uncertainties.get(i, j));
//                index++;
//            }
            // Nov. 2016: only provide the SD (diagonal terms), not the full matrix!
            targetSamples[numTargetParameters + index].set(uncertainties.get(i, i));
            index++;
        }

        int offset = numTargetParameters + numTargetUncertainties;
        targetSamples[offset].set(entropy);
        targetSamples[offset + TRG_REL_ENTROPY].set(relEntropy);
        targetSamples[offset + TRG_WEIGHTED_NUM_SAMPLES].set(weightedNumberOfSamples);
        targetSamples[offset + TRG_GOODNESS_OF_FIT].set(goodnessOfFit);
        targetSamples[offset + TRG_DAYS_TO_THE_CLOSEST_SAMPLE].set(daysToTheClosestSample);

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

    private void configurePriorSourceSamples(SampleConfigurer configurator) {
        int offset = 0;
        for (int i = 0; i < totalNumSdrBands; i++) {
            for (int j = 0; j < NUM_ALBEDO_PARAMETERS; j++) {
                // priorMeanBandNamePrefix = priorSdBandNamePrefix = 'BRDF_Albedo_Parameters_'
                // spectral:
//                float BRDF_Albedo_Parameters_Band1_f0_avr(y, x) ;
//                float BRDF_Albedo_Parameters_Band1_f0_sd(y, x) ;
//                float BRDF_Albedo_Parameters_Band1_f1_avr(y, x) ;
//                float BRDF_Albedo_Parameters_Band1_f1_sd(y, x) ;
//                float BRDF_Albedo_Parameters_Band1_f2_avr(y, x) ;
//                float BRDF_Albedo_Parameters_Band1_f2_sd(y, x) ;
//                float BRDF_Albedo_Parameters_Band1_wns(y, x) ;
//                float BRDF_Albedo_Parameters_Band2_f0_avr(y, x) ;
//                float BRDF_Albedo_Parameters_Band2_f0_sd(y, x) ;
//                float BRDF_Albedo_Parameters_Band2_f1_avr(y, x) ;
//                float BRDF_Albedo_Parameters_Band2_f1_sd(y, x) ;
//                float BRDF_Albedo_Parameters_Band2_f2_avr(y, x) ;
//                float BRDF_Albedo_Parameters_Band2_f2_sd(y, x) ;
//                float BRDF_Albedo_Parameters_Band2_wns(y, x) ;
//                ...
//                float BRDF_Albedo_Parameters_Band7_f2_sd(y, x) ;
//                float BRDF_Albedo_Parameters_Band7_wns(y, x) ;
//                float snowFraction(y, x) ;
//                byte landWaterType(y, x) ;

                final String meanBandName = priorMeanBandNamePrefix + "Band" + (i + 1) + "_f" + j + "_avr";
                configurator.defineSample(offset++, meanBandName, priorProduct);

                final String sdBandName = priorMeanBandNamePrefix + "Band" + (i + 1) + "_f" + j + "_sd";
                ;
                configurator.defineSample(offset++, sdBandName, priorProduct);
            }
        }
        String snowFractionBandName = "snowFraction";
        configurator.defineSample(offset++, snowFractionBandName, priorProduct);
        String landWaterBandName = "landWaterType";
        configurator.defineSample(offset++, landWaterBandName, priorProduct);
    }


    public static class Spi extends OperatorSpi {

        public Spi() {
            super(SpectralInversionWithPriorsOp.class);
        }
    }
}