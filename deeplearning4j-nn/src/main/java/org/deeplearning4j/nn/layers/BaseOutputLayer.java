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

package org.deeplearning4j.nn.layers;

import org.apache.commons.math3.analysis.differentiation.GradientFunction;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.api.Updater;
import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.activations.ActivationsFactory;
import org.deeplearning4j.nn.api.gradients.Gradients;
import org.deeplearning4j.nn.api.gradients.GradientsFactory;
import org.deeplearning4j.nn.api.layers.IOutputLayer;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.params.DefaultParamInitializer;
import org.deeplearning4j.optimize.Solver;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.api.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.ILossFunction;
import org.nd4j.linalg.primitives.Pair;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;


/**
 * Output layer with different objective
 * in co-occurrences for different objectives.
 * This includes classification as well as prediction
 * @author Adam Gibson
 *
 */
public abstract class BaseOutputLayer<LayerConfT extends org.deeplearning4j.nn.conf.layers.BaseOutputLayer>
                extends BaseLayer<LayerConfT> implements Serializable, IOutputLayer {

    //current input and label matrices
    protected INDArray labels;

    private transient Solver solver;

    private double fullNetworkL1;
    private double fullNetworkL2;
    protected double score;

    protected INDArray inputMaskArray;
    protected MaskState inputMaskArrayState;

    public BaseOutputLayer(NeuralNetConfiguration conf) {
        super(conf);
    }

    public BaseOutputLayer(NeuralNetConfiguration conf, INDArray input) {
        super(conf, input);
    }

    @Override
    public double score(){
        return score;
    }

    /** Compute score after labels and input have been set.
     * @param fullNetworkL1 L1 regularization term for the entire network
     * @param fullNetworkL2 L2 regularization term for the entire network
     * @param training whether score should be calculated at train or test time (this affects things like application of
     *                 dropout, etc)
     * @return score (loss function)
     */
    @Override
    public double computeScore(double fullNetworkL1, double fullNetworkL2, boolean training) {
        if (input == null || labels == null)
            throw new IllegalStateException("Cannot calculate score without input and labels " + layerId());
        this.fullNetworkL1 = fullNetworkL1;
        this.fullNetworkL2 = fullNetworkL2;
        INDArray preOut = preOutput2d(training);

        ILossFunction lossFunction = layerConf().getLossFn();

        //double score = lossFunction.computeScore(getLabels2d(), preOut, layerConf().getActivationFunction(), maskArray, false);
        double score = lossFunction.computeScore(getLabels2d(), preOut, layerConf().getActivationFn(), maskArray,
                        false);
        score += fullNetworkL1 + fullNetworkL2;
        score /= getInputMiniBatchSize();

        this.score = score;

        return score;
    }

    /**Compute the score for each example individually, after labels and input have been set.
     *
     * @param fullNetworkL1 L1 regularization term for the entire network (or, 0.0 to not include regularization)
     * @param fullNetworkL2 L2 regularization term for the entire network (or, 0.0 to not include regularization)
     * @return A column INDArray of shape [numExamples,1], where entry i is the score of the ith example
     */
    @Override
    public INDArray computeScoreForExamples(double fullNetworkL1, double fullNetworkL2) {
        if (input == null || labels == null)
            throw new IllegalStateException("Cannot calculate score without input and labels " + layerId());
        INDArray preOut = preOutput2d(false);

        ILossFunction lossFunction = layerConf().getLossFn();
        INDArray scoreArray =
                        lossFunction.computeScoreArray(getLabels2d(), preOut, layerConf().getActivationFn(), maskArray);
        double l1l2 = fullNetworkL1 + fullNetworkL2;
        if (l1l2 != 0.0) {
            scoreArray.addi(l1l2);
        }
        return scoreArray;
    }

    @Override
    public void computeGradientAndScore() {
        if (input == null || labels == null)
            return;

        INDArray preOut = preOutput2d(true);
        Gradients pair = getGradientsAndDelta(preOut);
        this.gradient = pair.getFirst();

        score = computeScore(fullNetworkL1, fullNetworkL2, true);
    }

    @Override
    public Pair<Gradient, Double> gradientAndScore() {
        return new Pair<>(gradient(), score());
    }

    @Override
    public Gradients backpropGradient(Gradients epsilons) {

        Gradients pair = getGradientsAndDelta(preOutput2d(true)); //Returns Gradient and delta^(this), not Gradient and epsilon^(this-1)
        INDArray delta = pair.getSecond();

        INDArray epsilonNext = getParamWithNoise(DefaultParamInitializer.WEIGHT_KEY, true).mmul(delta.transpose()).transpose();

        //Normally we would clear weightNoiseParams here - but we want to reuse them for forward + backward + score
        // So this is instead done in MultiLayerNetwork/CompGraph backprop methods

        return GradientsFactory.getInstance().create(epsilonNext, pair.getFirst());
    }

    /**
     * Gets the gradient from one training iteration
     * @return the gradient (bias and weight matrix)
     */
    @Override
    public Gradient gradient() {
        return gradient;
    }

    /** Returns tuple: {Gradient,Delta,Output} given preOut */
    private Gradients getGradientsAndDelta(INDArray preOut) {
        ILossFunction lossFunction = layerConf().getLossFn();
        INDArray labels2d = getLabels2d();
        //INDArray delta = lossFunction.computeGradient(labels2d, preOut, layerConf().getActivationFunction(), maskArray);
        INDArray delta = lossFunction.computeGradient(labels2d, preOut, layerConf().getActivationFn(), maskArray);

        Gradient gradient = new DefaultGradient();

        INDArray weightGradView = gradientViews.get(DefaultParamInitializer.WEIGHT_KEY);
        Nd4j.gemm(input, delta, weightGradView, true, false, 1.0, 0.0); //Equivalent to:  weightGradView.assign(input.transpose().mmul(delta));
        gradient.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, weightGradView);

        if(hasBias()){
            INDArray biasGradView = gradientViews.get(DefaultParamInitializer.BIAS_KEY);
            delta.sum(biasGradView, 0); //biasGradView is initialized/zeroed first in sum op
            gradient.gradientForVariable().put(DefaultParamInitializer.BIAS_KEY, biasGradView);
        }

        return new Pair<>(gradient, delta);
    }


    @Override
    public Activations activate(Activations input, boolean training) {
        if(input.size() != 1)
            throw new IllegalStateException();
        setInput(input.get(0));
        return ActivationsFactory.getInstance().create(output(training), null, null);
    }

    public INDArray output(INDArray input, boolean training) {
        setInput(input);
        return output(training);
    }

    public INDArray output(INDArray input) {
        setInput(input);
        return output(false);
    }

    /**
     * Classify input
     * @param training determines if its training
     * the input (can either be a matrix or vector)
     * If it's a matrix, each row is considered an example
     * and associated rows are classified accordingly.
     * Each row will be the likelihood of a label given that example
     * @return a probability distribution for each row
     */
    public INDArray output(boolean training) {
        if (input == null) {
            throw new IllegalArgumentException("Cannot perform forward pass with null input - " + layerId());
        }
        return super.activate(training).get(0);
    }

    @Override
    public void fit(DataSetIterator iter) {
        while (iter.hasNext())
            fit(iter.next());
    }

    /**
     * Fit the model
     *
     * @param input the examples to classify (one example in each row)
     * @param labels   the example labels(a binary outcome matrix)
     */
    @Override
    public void fit(INDArray input, INDArray labels) {
        setInput(input);
        setLabels(labels);
        applyDropOutIfNecessary(true);
        if (solver == null) {
            solver = new Solver.Builder().configure(conf()).listeners(getListeners()).model(this).build();
            //Set the updater state view array. For MLN and CG, this is done by MultiLayerUpdater and ComputationGraphUpdater respectively
            Updater updater = solver.getOptimizer().getUpdater();
            int updaterStateSize = 0;
            Map<String, INDArray> paramTable = paramTable();
            for (Map.Entry<String, INDArray> entry : paramTable.entrySet()) {
                updaterStateSize += (int) conf().getLayer().getUpdaterByParam(entry.getKey())
                                .stateSize(entry.getValue().length());
            }
            if (updaterStateSize > 0)
                updater.setStateViewArray(this, Nd4j.createUninitialized(new int[] {1, updaterStateSize}, Nd4j.order()),
                                true);
        }
        solver.optimize();
    }

    /**
     * Fit the model
     *
     * @param data the data to train on
     */
    @Override
    public void fit(DataSet data) {
        fit(data.getFeatures(), data.getLabels());
    }

    @Override
    public void clear() {
        super.clear();
        labels = null;
        solver = null;
    }

    /**
     * Fit the model to the given data
     *
     * @param data the data to fit the model to
     */
    @Override
    public void fit(INDArray data) {
        //no-op

    }

    @Override
    public INDArray getLabels() {
        return labels;
    }

    public void setLabels(INDArray labels) {
        this.labels = labels;
    }

    protected INDArray preOutput2d(boolean training) {
        return preOutput(training);
    }

    @Override
    protected void applyMask(INDArray to) {
        //For output layers: can be either per-example masking, or per-
        if (maskArray.isColumnVector()) {
            to.muliColumnVector(maskArray);
        } else if (Arrays.equals(to.shape(), maskArray.shape())) {
            to.muli(maskArray);
        } else {
            throw new IllegalStateException("Invalid mask array: per-example masking should be a column vector, "
                            + "per output masking arrays should be the same shape as the output/labels arrays. Mask shape: "
                            + Arrays.toString(maskArray.shape()) + ", output shape: " + Arrays.toString(to.shape())
                            + layerId());
        }
    }


    protected INDArray getLabels2d() {
        if (labels.rank() > 2) {
            return labels.reshape(labels.size(2), labels.size(1));
        }
        return labels;
    }

    @Override
    public boolean isPretrainLayer() {
        return false;
    }

    @Override
    public boolean hasBias() {
        return layerConf().hasBias();
    }




    @Override
    public void init() {
        //No op
    }

    @Override
    public void setListeners(Collection<IterationListener> listeners) {
        //No op
    }

    @Override
    public void setListeners(IterationListener... listeners) {
        //No op
    }

    @Override
    public void addListeners(IterationListener... listener) {
        //No op
    }

    @Override
    public Collection<IterationListener> getListeners() {
        return Collections.emptyList();
    }

    @Override
    public void fit() {
        //No op
    }

    @Override
    public ConvexOptimizer getOptimizer() {
        return null;
    }
}
