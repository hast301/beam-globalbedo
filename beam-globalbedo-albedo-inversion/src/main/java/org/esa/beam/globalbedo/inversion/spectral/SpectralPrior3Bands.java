package org.esa.beam.globalbedo.inversion.spectral;

import Jama.LUDecomposition;
import Jama.Matrix;
import org.esa.beam.framework.gpf.pointop.Sample;
import org.esa.beam.globalbedo.inversion.AlbedoInversionConstants;
import org.esa.beam.globalbedo.inversion.InversionOp;
import org.esa.beam.globalbedo.inversion.util.AlbedoInversionUtils;

import static org.esa.beam.globalbedo.inversion.AlbedoInversionConstants.NUM_ALBEDO_PARAMETERS;

/**
 * Object holding the prior data elements M, V, Mask and Parameters
 *
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
public class SpectralPrior3Bands {

    private Matrix M;
    private Matrix V;
    private double mask;
    private Matrix parameters;
    private double priorValidPixelFlag;
    private int[] bandIndices;

    private static int numSdrBands = 3;

    public SpectralPrior3Bands(Matrix m, Matrix v, double mask, Matrix parameters,
                               double priorValidPixelFlag,
                               int[] bandIndices) {
        this.M = m;
        this.V = v;
        this.mask = mask;
        this.parameters = parameters;
        this.priorValidPixelFlag = priorValidPixelFlag;
        this.bandIndices = bandIndices;
    }

    /**
     * Returns a prior object built from source samples of a prior product to be used for inversion in {@link InversionOp}}.
     * This method basically represents the BB implementation 'GetPrior'
     *
     * @param sourceSamples    - the source samples as defined in {@link InversionOp}}.
     * @param priorScaleFactor - the prior scale factor
     * @param computeSnow
     * @return Prior
     */
    public static SpectralPrior3Bands createForInversion(Sample[] sourceSamples, double priorScaleFactor,
                                                         boolean computeSnow,
                                                         int[] bandIndices) {

        Matrix C = new Matrix(NUM_ALBEDO_PARAMETERS, 3 * numSdrBands);
        Matrix inverseC = new Matrix(NUM_ALBEDO_PARAMETERS, 3 * numSdrBands);
        Matrix inverseC_F = new Matrix(3 * numSdrBands, 1);  // 9x1

        Matrix priorMean = new Matrix(AlbedoInversionConstants.NUM_ALBEDO_PARAMETERS, numSdrBands, 1);
        Matrix priorSD = new Matrix(AlbedoInversionConstants.NUM_ALBEDO_PARAMETERS, numSdrBands, 1);

        int index = 0;
        for (int i = 0; i < numSdrBands; i++) {
            for (int j = 0; j < AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; j++) {
                final int priorIndexMij = InversionOp.SRC_PRIOR_MEAN[bandIndices[i]][j];
                final double m_ij_value = sourceSamples[priorIndexMij].getDouble();
                final double m_ij = AlbedoInversionUtils.isValid(m_ij_value) ? m_ij_value : 0.0;
                priorMean.set(index, 0, m_ij);
                final int priorIndexSDij = InversionOp.SRC_PRIOR_SD[bandIndices[i]][j];
                final double cov_ij_value = sourceSamples[priorIndexSDij].getDouble();
                final double sd_ij = AlbedoInversionUtils.isValid(cov_ij_value) ? Math.sqrt(cov_ij_value) : 0.0;
                priorSD.set(index, 0, sd_ij);
                if (priorMean.get(index, 0) > 0.0 && priorSD.get(index, 0) == 0.0) {
                    priorSD.set(index, 0, 1.0);
                }
                index++;
            }
        }

        int offset = 7 * 2 * NUM_ALBEDO_PARAMETERS;
        double priorSnowFraction = sourceSamples[offset++].getDouble();
        int landWaterType = sourceSamples[offset].getInt();

        for (int i = 0; i < numSdrBands * AlbedoInversionConstants.NUM_ALBEDO_PARAMETERS; i++) {
            // priorSD.set(i, 0, Math.min(1.0, priorSD.get(i, 0) * priorScaleFactor * 1.0)); // original
            // this will lead to higher weighting of the Prior:
            priorSD.set(i, 0, Math.min(1.0, priorSD.get(bandIndices[i], 0) * priorScaleFactor * 0.01));  // todo: make configurable!
            C.set(bandIndices[i], i, priorSD.get(bandIndices[i], 0) * priorSD.get(bandIndices[i], 0));
        }

        double mask = 1.0;
        LUDecomposition lud = new LUDecomposition(C);
        // first check if pixel is regarded as valid
        double priorValidPixelFlag = validatePixel(computeSnow, priorSnowFraction, landWaterType, priorMean);
        if (priorValidPixelFlag >= 0) {  // we accept now BRDF f-params > 1.0
            // # Calculate C inverse
            // simplified condition (GL, 20110407)
            if (lud.isNonsingular()) {
                inverseC = C.inverse();
            } else {
                final Matrix cIdentity = Matrix.identity(NUM_ALBEDO_PARAMETERS, 3 * numSdrBands);
                inverseC = cIdentity.inverse();
            }
            setInverseC_F(inverseC, inverseC_F, priorMean);
        } else {
            mask = 0.0;
        }

        return new SpectralPrior3Bands(inverseC, inverseC_F, mask, priorMean, priorValidPixelFlag, bandIndices);
    }

    private static void setInverseC_F(Matrix inverseC, Matrix inverseC_F, Matrix priorMean) {
        int index;
        index = 0;
        for (int i = 0; i < AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; i++) {
            for (int j = 0; j < AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; j++) {
                inverseC_F.set(index, 0, inverseC.get(index, index) * priorMean.get(index, 0));
                index++;
            }
        }
    }

    private static double validatePixel(boolean computeSnow, double priorSnowFraction, int landWaterType, Matrix priorMean) {
        double returnValue = 0;
        int index = 0;
        for (int i = 0; i < numSdrBands; i++) {
            for (int j = 0; j < NUM_ALBEDO_PARAMETERS; j++) {
//                final boolean priorMeanTooLow = priorMean.get(index, 0) <= 0.0 || priorMean.get(index, 0) > 1.0;
                // 20171107: allow f-parameters > 1.0 to avoid gaps in snow processing (i.e. Antarctzica, Greenland)
                final boolean priorMeanNoData = priorMean.get(index, 0) == 0.0;
                final boolean priorMeanTooLow = priorMean.get(index, 0) <= 0.0;
                final boolean priorMeanTooHigh = priorMean.get(index, 0) > 1.0;
                final boolean priorSnowFractionNotOk = (computeSnow && priorSnowFraction <= 0.03) ||
                        (!computeSnow && priorSnowFraction >= 0.93);

                if (landWaterType <= 0 || priorMeanNoData) {
                    returnValue = AlbedoInversionConstants.NO_DATA_VALUE;
                } else if (priorSnowFractionNotOk) {
                    returnValue = -2.0;
                    break;
                } else if (priorMeanTooLow) {
                    returnValue = -1.0;
                    break;
                } else if (priorMeanTooHigh) {
                    returnValue = 1.0;
                    break;
                }
                index++;
            }
        }
        return returnValue;
    }


    public Matrix getM() {
        return M;
    }

    public Matrix getV() {
        return V;
    }

    public double getMask() {
        return mask;
    }

    public Matrix getParameters() {
        return parameters;
    }

    public double getPriorValidPixelFlag() {
        return priorValidPixelFlag;
    }
}