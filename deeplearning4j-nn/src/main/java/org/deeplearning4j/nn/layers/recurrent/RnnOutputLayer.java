/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */
package org.deeplearning4j.nn.layers.recurrent;

import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.activations.ActivationsFactory;
import org.deeplearning4j.nn.api.gradients.Gradients;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.layers.BaseOutputLayer;
import org.deeplearning4j.nn.params.DefaultParamInitializer;
import org.deeplearning4j.util.TimeSeriesUtils;
import org.nd4j.linalg.activations.impl.ActivationSoftmax;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.transforms.SoftMax;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.ILossFunction;

import java.util.Arrays;

/**Recurrent Neural Network Output Layer.<br>
 * Handles calculation of gradients etc for various objective functions.<br>
 * Functionally the same as OutputLayer, but handles output and label reshaping
 * automatically.<br>
 * Input and output activations are same as other RNN layers: 3 dimensions with shape
 * [miniBatchSize,nIn,timeSeriesLength] and [miniBatchSize,nOut,timeSeriesLength] respectively.
 * @author Alex Black
 * @see BaseOutputLayer, OutputLayer
 */
public class RnnOutputLayer extends BaseOutputLayer<org.deeplearning4j.nn.conf.layers.RnnOutputLayer> {

    public RnnOutputLayer(NeuralNetConfiguration conf) {
        super(conf);
    }

    @Override
    public Gradients backpropGradient(Gradients epsilon) {
        INDArray input = this.input.get(0);
        if (input.rank() != 3)
            throw new UnsupportedOperationException(
                            "Input is not rank 3. Got input with rank " + input.rank() + " " + layerId());
        INDArray inputTemp = input;
        this.input.set(0, TimeSeriesUtils.reshape3dTo2d(input));
        Gradients gradAndEpsilonNext = super.backpropGradient(epsilon);
        this.input.set(0, inputTemp);
        INDArray epsilon2d = gradAndEpsilonNext.get(0);
        INDArray epsilon3d = TimeSeriesUtils.reshape2dTo3d(epsilon2d, inputTemp.size(0));

        weightNoiseParams.clear();

        gradAndEpsilonNext.set(0, epsilon3d);
        return gradAndEpsilonNext;
    }

    @Override
    protected INDArray preOutput2d(boolean training) {
        INDArray input = this.input.get(0);
        if (input.rank() == 3) {
            //Case when called from RnnOutputLayer
            INDArray inputTemp = input;
            input = TimeSeriesUtils.reshape3dTo2d(input);
            INDArray out = super.preOutput(training);
            this.input.set(0, inputTemp);
            return out;
        } else {
            //Case when called from BaseOutputLayer
            INDArray out = super.preOutput(training);
            return out;
        }
    }

    @Override
    protected INDArray getLabels2d() {
        if (labels.rank() == 3)
            return TimeSeriesUtils.reshape3dTo2d(labels);
        return labels;
    }

    @Override
    public Activations output(INDArray input) {
        if (input.rank() != 3)
            throw new IllegalArgumentException("Input must be rank 3 (is: " + input.rank() + ") " + layerId());
        //Returns 3d activations from 3d input
        setInput(input);
        return output(false);
    }

    @Override
    public Activations output(boolean training) {
        INDArray input = this.input.get(0);
        //Assume that input is 3d
        if (input.rank() != 3)
            throw new IllegalArgumentException(
                            "input must be rank 3. Got input with rank " + input.rank() + " " + layerId());
        INDArray preOutput2d = preOutput2d(training);

        //if(conf.getLayer().getActivationFunction().equals("softmax")) {
        if (layerConf().getActivationFn() instanceof ActivationSoftmax) {
            INDArray out2d = Nd4j.getExecutioner().execAndReturn(new SoftMax(preOutput2d));
            if (this.input.getMask(0) != null) {
                out2d.muliColumnVector(this.input.getMask(0));
            }
            INDArray ret = TimeSeriesUtils.reshape2dTo3d(out2d, input.size(0));
            return ActivationsFactory.getInstance().create(ret, null, null);    //TODO masks
        }

        applyDropOutIfNecessary(training);
        INDArray origInput = input;
        this.input.set(0, TimeSeriesUtils.reshape3dTo2d(input));
        Activations out = super.activate(true);
        this.input.set(0, origInput);
        if (this.input.getMask(0) != null) {
            out.get(0).muliColumnVector(this.input.getMask(0));
        }
        out.set(0, TimeSeriesUtils.reshape2dTo3d(out.get(0), input.size(0)));
        return out;
    }

    @Override
    public Activations activate(boolean training) {
        INDArray input = this.input.get(0);
        if (input.rank() != 3)
            throw new UnsupportedOperationException(
                            "Input must be rank 3. Got input with rank " + input.rank() + " " + layerId());
        INDArray b = getParamWithNoise(DefaultParamInitializer.BIAS_KEY, training);
        INDArray W = getParamWithNoise(DefaultParamInitializer.WEIGHT_KEY, training);

        INDArray input2d = TimeSeriesUtils.reshape3dTo2d(input);

        //INDArray act2d = Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform(conf.getLayer().getActivationFunction(),
        //        input2d.mmul(W).addiRowVector(b)));
        INDArray act2d = layerConf().getActivationFn().getActivation(input2d.mmul(W).addiRowVector(b), training);
        if (this.input.getMask(0) != null) {
            act2d.muliColumnVector(this.input.getMask(0));
        }
        INDArray ret = TimeSeriesUtils.reshape2dTo3d(act2d, input.size(0));
        return ActivationsFactory.getInstance().create(ret);    //TODO masks
    }

    @Override
    public void setMaskArray(int idx, INDArray maskArray) {
        if (maskArray != null) {
            //Two possible cases:
            //(a) per time step masking - rank 2 mask array -> reshape to rank 1 (column vector)
            //(b) per output masking - rank 3 mask array  -> reshape to rank 2 (
            if (maskArray.rank() == 2) {
                this.input.setMask(idx, TimeSeriesUtils.reshapeTimeSeriesMaskToVector(maskArray));
            } else if (maskArray.rank() == 3) {
                this.input.setMask(idx, TimeSeriesUtils.reshape3dTo2d(maskArray));
            } else {
                throw new UnsupportedOperationException(
                                "Invalid mask array: must be rank 2 or 3 (got: rank " + maskArray.rank() + ", shape = "
                                                + Arrays.toString(maskArray.shape()) + ") " + layerId());
            }
        } else {
            this.input.setMask(idx, null);
        }
    }

//    @Override
//    public Pair<INDArray, MaskState> feedForwardMaskArray(INDArray maskArray, MaskState currentMaskState,
//                    int minibatchSize) {
//
//        //If the *input* mask array is present and active, we should use it to mask the output
//        if (maskArray != null && currentMaskState == MaskState.Active) {
//            this.inputMaskArray = TimeSeriesUtils.reshapeTimeSeriesMaskToVector(maskArray);
//            this.inputMaskArrayState = currentMaskState;
//        } else {
//            this.inputMaskArray = null;
//            this.inputMaskArrayState = null;
//        }
//
//        return null; //Last layer in network
//    }

    /**Compute the score for each example individually, after labels and input have been set.
     *
     * @param fullNetworkL1 L1 regularization term for the entire network (or, 0.0 to not include regularization)
     * @param fullNetworkL2 L2 regularization term for the entire network (or, 0.0 to not include regularization)
     * @return A column INDArray of shape [numExamples,1], where entry i is the score of the ith example
     */
    @Override
    public INDArray computeScoreForExamples(double fullNetworkL1, double fullNetworkL2) {
        //For RNN: need to sum up the score over each time step before returning.

        if (input == null || labels == null)
            throw new IllegalStateException("Cannot calculate score without input and labels " + layerId());
        INDArray preOut = preOutput2d(false);

        ILossFunction lossFunction = layerConf().getLossFn();
        INDArray scoreArray =
                        lossFunction.computeScoreArray(getLabels2d(), preOut, layerConf().getActivationFn(), input.getMask(0));
        //scoreArray: shape [minibatch*timeSeriesLength, 1]
        //Reshape it to [minibatch, timeSeriesLength] then sum over time step

        INDArray scoreArrayTs = TimeSeriesUtils.reshapeVectorToTimeSeriesMask(scoreArray, input.get(0).size(0));
        INDArray summedScores = scoreArrayTs.sum(1);

        double l1l2 = fullNetworkL1 + fullNetworkL2;
        if (l1l2 != 0.0) {
            summedScores.addi(l1l2);
        }

        return summedScores;
    }
}
