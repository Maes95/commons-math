/*
 * Copyright 2003-2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.math.distribution;


/**
 * Base interface for discrete distributions.
 *
 * @version $Revision$ $Date$
 */
public interface DiscreteDistribution extends Distribution {
    /**
     * For a random variable X whose values are distributed according
     * to this distribution, this method returns P(X = x). In other words, this
     * method represents the probability mass function, or PMF for the distribution.
     * 
     * @param x the value at which the probability mass function is evaluated.
     * @return the value of the probability mass function at x
     */
    double probability(double x);
}
