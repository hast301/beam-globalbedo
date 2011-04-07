package org.esa.beam.globalbedo.inversion;

import Jama.LUDecomposition;
import Jama.Matrix;
import org.esa.beam.framework.gpf.experimental.PointOperator;

/**
 * Object holding the prior data elements M, V, Mask and Parameters
 *
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
public class Prior {

    private Matrix M;
    private Matrix V;
    private int mask;
    private double[][] parameters;

    public Prior(Matrix m, Matrix v, int mask, double[][] parameters) {
        this.M = m;
        this.V = v;
        this.mask = mask;
        this.parameters = parameters;
    }

    /**
     * Returns a prior object built from source samples of a prior product to be used for inversion in {@link InversionOp}}.
     * This method basically represents the BB implementation 'GetPrior'
     *
     * @param sourceSamples - the source samples as defined in {@link InversionOp}}.
     * @return Prior
     */
    public static Prior createForInversion(PointOperator.Sample[] sourceSamples) {

        Matrix C = new Matrix(3 * AlbedoInversionConstants.numBBDRWaveBands,
                              3 * AlbedoInversionConstants.numBBDRWaveBands);              // 9x9
        Matrix inverseC = new Matrix(3 * AlbedoInversionConstants.numBBDRWaveBands,
                                     3 * AlbedoInversionConstants.numBBDRWaveBands);       // 9x9
        Matrix inverseC_F = new Matrix(3 * AlbedoInversionConstants.numBBDRWaveBands, 1);  // 9x1

        int mask = sourceSamples[InversionOp.SRC_PRIOR_MASK].getInt();
        double nSamples = sourceSamples[InversionOp.SRC_PRIOR_NSAMPLES].getDouble();

        final double priorScaleFactor = 10.0;

        double[][] priorMean = new double[AlbedoInversionConstants.numBBDRWaveBands][AlbedoInversionConstants.numBBDRWaveBands];
        double[][] priorSD = new double[AlbedoInversionConstants.numBBDRWaveBands][AlbedoInversionConstants.numBBDRWaveBands];

        for (int i = 0; i < AlbedoInversionConstants.numBBDRWaveBands; i++) {
            for (int j = 0; j < AlbedoInversionConstants.numBBDRWaveBands; j++) {
                priorMean[i][j] = sourceSamples[InversionOp.SRC_PRIOR_MEAN[i][j]].getDouble();
                priorSD[i][j] = sourceSamples[InversionOp.SRC_PRIOR_SD[i][j]].getDouble();
                if (priorMean[i][j] > 0.0 && priorSD[i][j] == 0.0) {
                    mask = 1;
                    nSamples = 1.E-20;
                    priorSD[i][j] = 1.0;
                }
                if (priorMean[i][j] > 0.0 && sourceSamples[InversionOp.SRC_PRIOR_SD[i][j]].getDouble() > 0.0 &&
                    sourceSamples[InversionOp.SRC_PRIOR_MASK].getInt() > 0) {
                    mask = 1;
                    nSamples = 1.E-20;
                    priorSD[i][j] = 1.0;
                }
            }
        }

        for (int i = 0; i < AlbedoInversionConstants.numBBDRWaveBands; i++) {
            for (int j = 0; j < AlbedoInversionConstants.numBBDRWaveBands; j++) {
                priorSD[i][j] = Math.min(1.0, priorSD[i][j] * priorScaleFactor);
                if (i == j) {
                    C.set(i, j, priorSD[i][j] * priorSD[i][j]);
                }
            }
        }

        // # Calculate C inverse
        // simplified condition (GL, 20110407)
        if  (nSamples > 0.0) {
            boolean processPixel = true;
            LUDecomposition lud = new LUDecomposition(C);
            if (lud.isNonsingular()) {
                inverseC = C.inverse();
            } else {
                for (int j = 0; j < AlbedoInversionConstants.numBBDRWaveBands; j++) {
                    if (priorMean[0][j] <= 0.0 || priorMean[0][j] > 1.0 || priorSD[0][j] <= 0.0 || priorSD[0][j] > 1.0) {
                        processPixel = false;
                        break;
                    }
                }
            }
            if (processPixel) {
                final Matrix cIdentity = Matrix.identity(3 * AlbedoInversionConstants.numBBDRWaveBands,
                                                         3 * AlbedoInversionConstants.numBBDRWaveBands);    // 9x9
                inverseC = cIdentity.inverse();
                int index = 0;
                for (int i = 0; i < AlbedoInversionConstants.numBBDRWaveBands; i++) {
                    for (int j = 0; j < AlbedoInversionConstants.numBBDRWaveBands; j++) {
                        inverseC_F.set(index, 0, inverseC.get(index, index) * priorMean[i][j]);
                        index++;
                    }
                }
            } else {
                mask = 0;
            }

        }

        return new Prior(inverseC, inverseC_F, mask, priorMean);
    }

    public Matrix getM() {
        return M;
    }

    public Matrix getV() {
        return V;
    }

    public int getMask() {
        return mask;
    }

    public double[][] getParameters() {
        return parameters;
    }
}