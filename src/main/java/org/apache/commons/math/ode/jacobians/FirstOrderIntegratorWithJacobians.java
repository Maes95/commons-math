/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.math.ode.jacobians;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;

import org.apache.commons.math.MathRuntimeException;
import org.apache.commons.math.ode.DerivativeException;
import org.apache.commons.math.ode.FirstOrderDifferentialEquations;
import org.apache.commons.math.ode.FirstOrderIntegrator;
import org.apache.commons.math.ode.IntegratorException;
import org.apache.commons.math.ode.sampling.StepHandler;
import org.apache.commons.math.ode.sampling.StepInterpolator;

/** This class enhances a first order integrator for differential equations to
 * compute also partial derivatives of the solution with respect to initial state
 * and parameters.
 * <p>In order to compute both the state and its derivatives, the ODE problem
 * is extended with jacobians of the raw ODE and the variational equations are
 * added to form a new compound problem of higher dimension. If the original ODE
 * problem has dimension n and there are p parameters, the compound problem will
 * have dimension n &times; (1 + n + k).</p>
 * @see ParameterizedODE
 * @see ParameterizedODEWithJacobians
 * @version $Revision$ $Date$
 * @since 2.1
 */
public class FirstOrderIntegratorWithJacobians {

    /** Underlying integrator for compound problem. */
    private final FirstOrderIntegrator integrator;

    /** Raw equations to integrate. */
    private final ParameterizedODEWithJacobians ode;

    /** Build an enhanced integrator using internal differentiation to compute jacobians.
     * @param integrator underlying integrator to solve the compound problem
     * @param ode original problem (f in the equation y' = f(t, y))
     * @param p parameters array (may be null if {@link
     * ParameterizedODE#getParametersDimension()
     * getParametersDimension()} from original problem is zero)
     * @param hY step sizes to use for computing the jacobian df/dy, must have the
     * same dimension as the original problem
     * @param hP step sizes to use for computing the jacobian df/dp, must have the
     * same dimension as the original problem parameters dimension
     * @see #EnhancedFirstOrderIntegrator(FirstOrderIntegrator,
     * ParameterizedODEWithJacobians)
     */
    public FirstOrderIntegratorWithJacobians(final FirstOrderIntegrator integrator,
                                             final ParameterizedODE ode,
                                             final double[] p, final double[] hY, final double[] hP) {
        checkDimension(ode.getDimension(), hY);
        checkDimension(ode.getParametersDimension(), p);
        checkDimension(ode.getParametersDimension(), hP);
        this.integrator = integrator;
        this.ode = new FiniteDifferencesWrapper(ode, p, hY, hP);
    }

    /** Build an enhanced integrator using ODE builtin jacobian computation features.
     * @param integrator underlying integrator to solve the compound problem
     * @param ode original problem, which can compute the jacobians by itself
     * @see #EnhancedFirstOrderIntegrator(FirstOrderIntegrator,
     * ParameterizedODE, double[], double[], double[])
     */
    public FirstOrderIntegratorWithJacobians(final FirstOrderIntegrator integrator,
                                             final ParameterizedODEWithJacobians ode) {
        this.integrator = integrator;
        this.ode = ode;
    }

    /** Add a step handler to this integrator.
     * <p>The handler will be called by the integrator for each accepted
     * step.</p>
     * @param handler handler for the accepted steps
     * @see #getStepHandlers()
     * @see #clearStepHandlers()
     */
    public void addStepHandler(StepHandlerWithJacobians handler) {
        integrator.addStepHandler(new StepHandlerWrapper(handler));
    }

    /** Get all the step handlers that have been added to the integrator.
     * @return an unmodifiable collection of the added events handlers
     * @see #addStepHandler(StepHandlerWithJacobians)
     * @see #clearStepHandlers()
     */
    public Collection<StepHandlerWithJacobians> getStepHandlers() {
        final Collection<StepHandlerWithJacobians> handlers =
            new ArrayList<StepHandlerWithJacobians>();
        for (final StepHandler handler : integrator.getStepHandlers()) {
            if (handler instanceof StepHandlerWrapper) {
                handlers.add(((StepHandlerWrapper) handler).getHandler());
            }
        }
        return handlers;
    }

    /** Remove all the step handlers that have been added to the integrator.
     * @see #addStepHandler(StepHandlerWithJacobians)
     * @see #getStepHandlers()
     */
    public void clearStepHandlers() {

        // preserve the handlers we did not add ourselves
        final Collection<StepHandler> otherHandlers = new ArrayList<StepHandler>();
        for (final StepHandler handler : integrator.getStepHandlers()) {
            if (!(handler instanceof StepHandlerWrapper)) {
                otherHandlers.add(handler);
            }
        }

        // clear all handlers
        integrator.clearStepHandlers();

        // put back the preserved handlers
        for (final StepHandler handler : otherHandlers) {
            integrator.addStepHandler(handler);
        }

    }

    /** Integrate the differential equations and the variational equations up to the given time.
     * <p>This method solves an Initial Value Problem (IVP) and also computes the derivatives
     * of the solution with respect to initial state and parameters. This can be used as
     * a basis to solve Boundary Value Problems (BVP).</p>
     * <p>Since this method stores some internal state variables made
     * available in its public interface during integration ({@link
     * #getCurrentSignedStepsize()}), it is <em>not</em> thread-safe.</p>
     * @param equations differential equations to integrate
     * @param t0 initial time
     * @param y0 initial value of the state vector at t0
     * @param dY0dP initial value of the state vector derivative with respect to the
     * parameters at t0
     * @param t target time for the integration
     * (can be set to a value smaller than <code>t0</code> for backward integration)
     * @param y placeholder where to put the state vector at each successful
     *  step (and hence at the end of integration), can be the same object as y0
     * @param dYdY0 placeholder where to put the state vector derivative with respect
     * to the initial state (dy[i]/dy0[j] is in element array dYdY0[i][j]) at each successful
     *  step (and hence at the end of integration)
     * @param dYdP placeholder where to put the state vector derivative with respect
     * to the parameters (dy[i]/dp[j] is in element array dYdP[i][j]) at each successful
     *  step (and hence at the end of integration)
     * @return stop time, will be the same as target time if integration reached its
     * target, but may be different if some event handler stops it at some point.
     * @throws IntegratorException if the integrator cannot perform integration
     * @throws DerivativeException this exception is propagated to the caller if
     * the underlying user function triggers one
     */
    public double integrate(final double t0, final double[] y0, final double[][] dY0dP,
                            final double t, final double[] y,
                            final double[][] dYdY0, final double[][] dYdP)
        throws DerivativeException, IntegratorException {

        final int n = ode.getDimension();
        final int k = ode.getParametersDimension();
        checkDimension(n, y0);
        checkDimension(n, y);
        checkDimension(n, dYdY0);
        checkDimension(n, dYdY0[0]);
        if (k != 0) {
            checkDimension(n, dY0dP);
            checkDimension(k, dY0dP[0]);
            checkDimension(n, dYdP);
            checkDimension(k, dYdP[0]);
        }

        // the compound state z contains the raw state y and its derivatives
        // with respect to initial state y0 and to parameters p
        //    y[i]         is stored in z[i]
        //    dy[i]/dy0[j] is stored in z[n + i * n + j]
        //    dy[i]/dp[j]  is stored in z[n * (n + 1) + i * k + j]
        final int q = n * (1 + n + k);

        // set up initial state, including partial derivatives
        final double[] z = new double[q];
        System.arraycopy(y0, 0, z, 0, n);
        for (int i = 0; i < n; ++i) {

            // set diagonal element of dy/dy0 to 1.0 at t = t0
            z[i * (1 + n) + n] = 1.0;

            // set initial derivatives with respect to parameters
            System.arraycopy(dY0dP[i], 0, z, n * (n + 1) + i * k, k);

        }

        // integrate the compound state variational equations
        final double stopTime = integrator.integrate(new FirstOrderDifferentialEquations() {

            /** Current state. */
            private final double[]   y    = new double[n];

            /** Time derivative of the current state. */
            private final double[]   yDot = new double[n];

            /** Derivatives of yDot with respect to state. */
            private final double[][] dFdY = new double[n][n];

            /** Derivatives of yDot with respect to parameters. */
            private final double[][] dFdP = new double[n][k];

            /** {@inheritDoc} */
            public int getDimension() {
                return q;
            }

            /** {@inheritDoc} */
            public void computeDerivatives(final double t, final double[] z, final double[] zDot)
                throws DerivativeException {

                // compute raw ODE and its jacobians: dy/dt, d[dy/dt]/dy0 and d[dy/dt]/dp
                System.arraycopy(z,    0, y,    0, n);
                ode.computeDerivatives(t, y, yDot);
                ode.computeJacobians(t, y, yDot, dFdY, dFdP);

                // state part of the compound equations
                System.arraycopy(yDot, 0, zDot, 0, n);

                // variational equations: from d[dy/dt]/dy0 to d[dy/dy0]/dt
                for (int i = 0; i < n; ++i) {
                    final double[] dFdYi = dFdY[i];
                    for (int j = 0; j < n; ++j) {
                        double s = 0;
                        final int startIndex = n + j;
                        int zIndex = startIndex;
                        for (int l = 0; l < n; ++l) {
                            s += dFdYi[l] * z[zIndex];
                            zIndex += n;
                        }
                        zDot[startIndex + i * n] = s;
                    }
                }

                // variational equations: from d[dy/dt]/dy0 and d[dy/dt]/dp to d[dy/dp]/dt
                for (int i = 0; i < n; ++i) {
                    final double[] dFdYi = dFdY[i];
                    final double[] dFdPi = dFdP[i];
                    for (int j = 0; j < k; ++j) {
                        double s = dFdPi[j];
                        final int startIndex = n * (n + 1) + j;
                        int zIndex = startIndex;
                        for (int l = 0; l < n; ++l) {
                            s += dFdYi[l] * z[zIndex];
                            zIndex += k;
                        }
                        zDot[startIndex + i * k] = s;
                    }
                }

            }

        }, t0, z, t, z);

        // dispatch the final compound state into the state and partial derivatives arrays
        System.arraycopy(z, 0, y, 0, n);
        for (int i = 0; i < n; ++i) {
            System.arraycopy(z, n * (i + 1), dYdY0[i], 0, n);
        }
        for (int i = 0; i < n; ++i) {
            System.arraycopy(z, n * (n + 1) + i * k, dYdP[i], 0, k);
        }

        return stopTime;

    }

    /** Check array dimensions.
     * @param expected expected dimension
     * @param array (may be null if expected is 0)
     * @throws IllegalArgumentException if the array dimension does not match the expected one
     */
    private void checkDimension(final int expected, final Object array)
        throws IllegalArgumentException {
        int arrayDimension = (array == null) ? 0 : Array.getLength(array);
        if (arrayDimension != expected) {
            throw MathRuntimeException.createIllegalArgumentException(
                  "dimension mismatch {0} != {1}", arrayDimension, expected);
        }
    }

    /** Wrapper class to compute jacobians by finite differences for ODE which do not compute them themselves. */
    private static class FiniteDifferencesWrapper
        implements ParameterizedODEWithJacobians {

        /** Raw ODE without jacobians computation. */
        private final ParameterizedODE ode;

        /** Parameters array (may be null if parameters dimension from original problem is zero) */
        private final double[] p;

        /** Step sizes to use for computing the jacobian df/dy. */
        private final double[] hY;

        /** Step sizes to use for computing the jacobian df/dp. */
        private final double[] hP;

        /** Temporary array for state derivatives used to compute jacobians. */
        private final double[] tmpDot;

        /** Simple constructor.
         * @param ode original ODE problem, without jacobians computations
         * @param p parameters array (may be null if parameters dimension from original problem is zero)
         * @param hY step sizes to use for computing the jacobian df/dy
         * @param hP step sizes to use for computing the jacobian df/dp
         */
        public FiniteDifferencesWrapper(final ParameterizedODE ode,
                                        final double[] p, final double[] hY, final double[] hP) {
            this.ode = ode;
            this.p  = p.clone();
            this.hY = hY.clone();
            this.hP = hP.clone();
            tmpDot = new double[ode.getDimension()];
        }

        /** {@inheritDoc} */
        public int getDimension() {
            return ode.getDimension();
        }

        /** {@inheritDoc} */
        public void computeDerivatives(double t, double[] y, double[] yDot) throws DerivativeException {
            ode.computeDerivatives(t, y, yDot);
        }

        /** {@inheritDoc} */
        public int getParametersDimension() {
            return ode.getParametersDimension();
        }

        /** {@inheritDoc} */
        public void setParameter(int i, double value) {
            ode.setParameter(i, value);
        }

        /** {@inheritDoc} */
        public void computeJacobians(double t, double[] y, double[] yDot,
                                     double[][] dFdY, double[][] dFdP)
            throws DerivativeException {

            final int n = ode.getDimension();
            final int k = ode.getParametersDimension();

            // compute df/dy where f is the ODE and y is the state array
            for (int j = 0; j < n; ++j) {
                final double savedYj = y[j];
                y[j] += hY[j];
                ode.computeDerivatives(t, y, tmpDot);
                for (int i = 0; i < n; ++i) {
                    dFdY[i][j] = (tmpDot[i] - yDot[i]) / hY[j];
                }
                y[j] = savedYj;
            }

            // compute df/dp where f is the ODE and p is the parameters array
            for (int j = 0; j < k; ++j) {
                ode.setParameter(j, p[j] +  hP[j]);
                ode.computeDerivatives(t, y, tmpDot);
                for (int i = 0; i < n; ++i) {
                    dFdP[i][j] = (tmpDot[i] - yDot[i]) / hP[j];
                }
                ode.setParameter(j, p[j]);
            }

        }

    }

    /** Wrapper for step handlers. */
    private class StepHandlerWrapper implements StepHandler {

        /** Underlying step handler with jacobians. */
        private final StepHandlerWithJacobians handler;

        /** Simple constructor.
         * @param handler underlying step handler with jacobians
         */
        public StepHandlerWrapper(final StepHandlerWithJacobians handler) {
            this.handler = handler;
        }

        /** Get the underlying step handler with jacobians.
         * @return underlying step handler with jacobians
         */
        public StepHandlerWithJacobians getHandler() {
            return handler;
        }

        /** {@inheritDoc} */
        public void handleStep(StepInterpolator interpolator, boolean isLast)
            throws DerivativeException {
            handler.handleStep(new StepInterpolatorWrapper(interpolator,
                                                           ode.getDimension(),
                                                           ode.getParametersDimension()),
                               isLast);
        }

        /** {@inheritDoc} */
        public boolean requiresDenseOutput() {
            return handler.requiresDenseOutput();
        }

        /** {@inheritDoc} */
        public void reset() {
            handler.reset();
        }

    }

    /** Wrapper for step interpolators. */
    private static class StepInterpolatorWrapper
        implements StepInterpolatorWithJacobians {

        /** Wrapped interpolator. */
        private StepInterpolator interpolator;

        /** State array. */
        private double[] y;

        /** State derivative array. */
        private double[] yDot;

        /** Jacobian with respect to initial state dy/dy0. */
        private double[][] dydy0;

        /** Jacobian with respect to parameters dy/dp. */
        private double[][] dydp;

        /** Simple constructor.
         * @param interpolator wrapped interpolator
         * @param n dimension of the original ODE
         * @param k number of parameters
         */
        public StepInterpolatorWrapper(final StepInterpolator interpolator,
                                       final int n, final int k) {
            this.interpolator = interpolator;
            y     = new double[n];
            yDot  = new double[n];
            dydy0 = new double[n][n];
            dydp  = new double[n][k];
        }

        /** {@inheritDoc} */
        public void setInterpolatedTime(double time) {
            interpolator.setInterpolatedTime(time);
        }

        /** {@inheritDoc} */
        public boolean isForward() {
            return interpolator.isForward();
        }

        /** {@inheritDoc} */
        public double getPreviousTime() {
            return interpolator.getPreviousTime();
        }

        /** {@inheritDoc} */
        public double getInterpolatedTime() {
            return interpolator.getInterpolatedTime();
        }

        /** {@inheritDoc} */
        public double[] getInterpolatedY() throws DerivativeException {
            double[] extendedState = interpolator.getInterpolatedState();
            System.arraycopy(extendedState, 0, y, 0, y.length);
            return y;
        }

        /** {@inheritDoc} */
        public double[][] getInterpolatedDyDy0() throws DerivativeException {
            double[] extendedState = interpolator.getInterpolatedState();
            final int n = y.length;
            int start = n;
            for (int i = 0; i < n; ++i) {
                System.arraycopy(extendedState, start, dydy0[i], 0, n);
                start += n;
            }
            return dydy0;
        }

        /** {@inheritDoc} */
        public double[][] getInterpolatedDyDp() throws DerivativeException {
            double[] extendedState = interpolator.getInterpolatedState();
            final int n = y.length;
            final int k = dydp[0].length;
            int start = n * (n + 1);
            for (int i = 0; i < n; ++i) {
                System.arraycopy(extendedState, start, dydp[i], 0, k);
                start += k;
            }
            return dydp;
        }

        /** {@inheritDoc} */
        public double[] getInterpolatedYDot() throws DerivativeException {
            double[] extendedDerivatives = interpolator.getInterpolatedDerivatives();
            System.arraycopy(extendedDerivatives, 0, yDot, 0, yDot.length);
            return yDot;
        }

        /** {@inheritDoc} */
        public double[][] getInterpolatedDyDy0Dot() throws DerivativeException {
            double[] extendedDerivatives = interpolator.getInterpolatedDerivatives();
            final int n = y.length;
            int start = n;
            for (int i = 0; i < n; ++i) {
                System.arraycopy(extendedDerivatives, start, dydy0[i], 0, n);
                start += n;
            }
            return dydy0;
        }

        /** {@inheritDoc} */
        public double[][] getInterpolatedDyDpDot() throws DerivativeException {
            double[] extendedDerivatives = interpolator.getInterpolatedDerivatives();
            final int n = y.length;
            final int k = dydp[0].length;
            int start = n * (n + 1);
            for (int i = 0; i < n; ++i) {
                System.arraycopy(extendedDerivatives, start, dydp[i], 0, k);
                start += k;
            }
            return dydp;
        }

        /** {@inheritDoc} */
        public double getCurrentTime() {
            return interpolator.getCurrentTime();
        }

        /** {@inheritDoc} */
        public StepInterpolatorWithJacobians copy() throws DerivativeException {
            final int n = y.length;
            final int k = dydp[0].length;
            StepInterpolatorWrapper copied =
                new StepInterpolatorWrapper(interpolator.copy(), n, k);
            System.arraycopy(y,    0, copied.y,    0, n);
            System.arraycopy(yDot, 0, copied.yDot, 0, n);
            for (int i = 0; i < n; ++i) {
                System.arraycopy(dydy0[i], 0, copied.dydy0[i], 0, n);
            }
            for (int i = 0; i < n; ++i) {
                System.arraycopy(dydp[i], 0, copied.dydp[i], 0, k);
            }
            return copied;
        }

        /** {@inheritDoc} */
        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(interpolator);
            final int n = y.length;
            final int k = dydp[0].length;
            out.writeInt(n);
            out.writeInt(k);
            for (int i = 0; i < n; ++i) {
                out.writeDouble(y[i]);
            }
            for (int i = 0; i < n; ++i) {
                out.writeDouble(yDot[i]);
            }
            for (int i = 0; i < n; ++i) {
                for (int j = 0; j < n; ++j) {
                    out.writeDouble(dydy0[i][j]);
                }
            }
            for (int i = 0; i < n; ++i) {
                for (int j = 0; j < k; ++j) {
                    out.writeDouble(dydp[i][j]);
                }
            }
        }

        /** {@inheritDoc} */
        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            interpolator = (StepInterpolator) in.readObject();
            final int n = in.readInt();
            final int k = in.readInt();
            y = new double[n];
            dydy0 = new double[n][n];
            dydp = new double[n][k];
            for (int i = 0; i < n; ++i) {
                y[i] = in.readDouble();
            }
            for (int i = 0; i < n; ++i) {
                yDot[i] = in.readDouble();
            }
            for (int i = 0; i < n; ++i) {
                for (int j = 0; j < n; ++j) {
                    dydy0[i][j] = in.readDouble();
                }
            }
            for (int i = 0; i < n; ++i) {
                for (int j = 0; j < k; ++j) {
                    dydp[i][j] = in.readDouble();
                }
            }
        }

    }
}