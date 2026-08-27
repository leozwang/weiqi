package com.cwave.weiqi.katago;

import android.util.Log;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.nnapi.NnApiDelegate;

/**
 * Executes KataGo neural network inference via TensorFlow Lite / LiteRT on Google Tensor TPU.
 * Handles NCHW (KataGo C++) to NHWC (TFLite) tensor permutation and dynamic output mapping.
 */
public class TfLiteTpuEvaluator {
    private static final String TAG = "TfLiteTpuEvaluator";
    private Interpreter mInterpreter;
    private NnApiDelegate mNnApiDelegate;

    private int mPolicyTensorIdx = -1;
    private int mValueTensorIdx = -1;
    private int mOwnershipTensorIdx = -1;
    private int evalCount = 0;

    public TfLiteTpuEvaluator(File modelFile) {
        try {
            NnApiDelegate.Options nnapiOptions = new NnApiDelegate.Options();
            nnapiOptions.setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_FAST_SINGLE_ANSWER);
            nnapiOptions.setAllowFp16(true);
            nnapiOptions.setUseNnapiCpu(false); // STRICT TPU: Reject silent fallback to CPU (XNNPACK)

            mNnApiDelegate = new NnApiDelegate(nnapiOptions);

            Interpreter.Options options = new Interpreter.Options();
            options.addDelegate(mNnApiDelegate);

            mInterpreter = new Interpreter(modelFile, options);

            // Dynamically map output tensor indices by shape
            int outputCount = mInterpreter.getOutputTensorCount();
            for (int i = 0; i < outputCount; i++) {
                int[] shape = mInterpreter.getOutputTensor(i).shape();
                int lastDim = (shape.length > 0) ? shape[shape.length - 1] : 0;
                if (lastDim == 362) {
                    mPolicyTensorIdx = i;
                } else if (lastDim == 7 || lastDim == 4) {
                    mValueTensorIdx = i;
                } else if (lastDim == 361) {
                    mOwnershipTensorIdx = i;
                }
            }

            Log.i(TAG, "TFLite TPU Interpreter created for " + modelFile.getName() + 
                " [Output map: policy=" + mPolicyTensorIdx + ", value=" + mValueTensorIdx + ", ownership=" + mOwnershipTensorIdx + "]");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create TFLite TPU Interpreter with hardware NnApiDelegate", e);
            close();
            mInterpreter = null;
        }
    }

    public boolean isInitialized() {
        return mInterpreter != null && mPolicyTensorIdx >= 0 && mValueTensorIdx >= 0;
    }

    /**
     * Performs forward inference pass on board features.
     */
    public boolean evaluate(
        float[] spatialInput,   // [22 * 19 * 19] = 7942 floats in NCHW layout
        float[] globalInput,    // [19] floats
        float[] policyOutput,   // [362] floats (361 board + 1 pass)
        float[] valueOutput,    // [7] floats (win, loss, noResult, scoreMean, scoreMeanSq, lead, varTimeLeft)
        float[] ownershipOutput // [361] floats
    ) {
        if (!isInitialized()) {
            Log.e(TAG, "Interpreter is not properly initialized.");
            return false;
        }

        try {
            // Permute KataGo NCHW float array [22, 19, 19] into TFLite NHWC tensor [1, 19, 19, 22]
            float[][][][] spatialTensor = new float[1][19][19][22];
            for (int c = 0; c < 22; c++) {
                int channelOffset = c * 19 * 19;
                for (int y = 0; y < 19; y++) {
                    int rowOffset = y * 19;
                    for (int x = 0; x < 19; x++) {
                        spatialTensor[0][y][x][c] = spatialInput[channelOffset + rowOffset + x];
                    }
                }
            }

            float[][] globalTensor = new float[1][19];
            System.arraycopy(globalInput, 0, globalTensor[0], 0, 19);

            Object[] inputs = new Object[] { spatialTensor, globalTensor };

            float[][] outPolicy = new float[1][362];
            int[] valueShape = mInterpreter.getOutputTensor(mValueTensorIdx).shape();
            int valueLen = (valueShape.length > 0) ? valueShape[valueShape.length - 1] : 7;
            float[][] outValue = new float[1][valueLen];
            float[][] outOwnership = new float[1][361];

            Map<Integer, Object> outputs = new HashMap<>();
            outputs.put(mPolicyTensorIdx, outPolicy);
            outputs.put(mValueTensorIdx, outValue);
            if (mOwnershipTensorIdx >= 0) {
                outputs.put(mOwnershipTensorIdx, outOwnership);
            }

            mInterpreter.runForMultipleInputsOutputs(inputs, outputs);

            System.arraycopy(outPolicy[0], 0, policyOutput, 0, 362);
            int copyLen = Math.min(valueLen, valueOutput.length);
            System.arraycopy(outValue[0], 0, valueOutput, 0, copyLen);
            if (mOwnershipTensorIdx >= 0) {
                System.arraycopy(outOwnership[0], 0, ownershipOutput, 0, 361);
            }

            evalCount++;
            if (evalCount == 1 || evalCount % 50 == 0) {
                float max1 = -9999f, max2 = -9999f, max3 = -9999f;
                int idx1 = -1, idx2 = -1, idx3 = -1;
                for (int i = 0; i < 362; i++) {
                    float val = outPolicy[0][i];
                    if (val > max1) {
                        max3 = max2; idx3 = idx2;
                        max2 = max1; idx2 = idx1;
                        max1 = val; idx1 = i;
                    } else if (val > max2) {
                        max3 = max2; idx3 = idx2;
                        max2 = val; idx2 = i;
                    } else if (val > max3) {
                        max3 = val; idx3 = i;
                    }
                }
                Log.i(TAG, String.format("TPU Eval #%d: Top Policy: #1[idx=%d, val=%.3f], #2[idx=%d, val=%.3f], #3[idx=%d, val=%.3f], passVal[361]=%.3f | Value[win=%.3f, loss=%.3f, score=%.3f]",
                    evalCount, idx1, max1, idx2, max2, idx3, max3, outPolicy[0][361], outValue[0][0], outValue[0][1], (outValue[0].length > 3 ? outValue[0][3] : 0.0f)));
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "TFLite TPU evaluation error", e);
            return false;
        }
    }

    public void close() {
        if (mInterpreter != null) {
            try {
                mInterpreter.close();
                mInterpreter = null;
            } catch (Exception e) {
                Log.e(TAG, "Error closing TFLite interpreter", e);
            }
        }
        if (mNnApiDelegate != null) {
            try {
                mNnApiDelegate.close();
                mNnApiDelegate = null;
            } catch (Exception e) {
                Log.e(TAG, "Error closing NNAPI delegate", e);
            }
        }
    }
}
