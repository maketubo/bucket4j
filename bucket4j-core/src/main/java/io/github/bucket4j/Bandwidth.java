/*
 *
 * Copyright 2015-2018 Vladimir Bukhtoyarov
 *
 *       Licensed under the Apache License, Version 2.0 (the "License");
 *       you may not use this file except in compliance with the License.
 *       You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package io.github.bucket4j;

import io.github.bucket4j.serialization.DeserializationBinding;
import io.github.bucket4j.serialization.Deserializer;
import io.github.bucket4j.serialization.SelfSerializable;
import io.github.bucket4j.serialization.SerializationBinding;

import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;

/**
 * <h3>Anatomy of bandwidth:</h3>
 * The bandwidth is key building block for bucket.
 * The bandwidth consists from {@link #capacity} and {@link Refill refill}. Where:
 * <ul>
 *     <li><b>Capacity</b> - defines the maximum count of tokens which can be hold by bucket.</li>
 *     <li><b>Refill</b> - defines the speed in which tokens are regenerated in bucket.</li>
 * </ul>
 *
 * <h3>Classic and simple bandwidth definitions:</h3>
 * The bandwidth can be initialized in the two way:
 * <ul>
 *     <li>{@link #simple(long, Duration) Simple} - most popular way, which does not require from you to fully understand the token-bucket algorithm.
 *     Use this way when you just want to specify easy limitation <tt>N</tt> tokens per <tt>M</tt> time window.
 *     See <a href="https://github.com/vladimir-bukhtoyarov/bucket4j/blob/1.3/doc-pages/basic-usage.md#example-1---limiting-the-rate-of-heavy-work">this example</a> of usage.
 *     </li>
 *     <li>{@link #classic(long, Refill)} Classic} - hard way to specify limitation,
 *     use it when you want to utilize the whole power of token-bucket. See <a href="https://github.com/vladimir-bukhtoyarov/bucket4j/blob/1.3/doc-pages/basic-usage.md#example-3---limiting-the-rate-of-access-to-rest-api">this example</a> of usage.
 *     </li>
 * </ul>
 *
 * <h3>Multiple bandwidths:</h3>
 * Most likely you will use only one bandwidth per bucket,
 * but in general it is possible to specify more than one bandwidth per bucket,
 * and bucket will handle all bandwidth in strongly atomic way.
 * Strongly atomic means that token will be consumed from all bandwidth or from nothing,
 * in other words any token can not be partially consumed.
 * <br> Example of multiple bandwidth:
 * <pre>{@code // Adds bandwidth that restricts to consume not often 1000 tokens per 1 minute and not often than 100 tokens per second
 * Bucket bucket = Bucket4j.builder().
 *      .addLimit(Bandwidth.create(1000, Duration.ofMinutes(1)));
 *      .addLimit(Bandwidth.create(100, Duration.ofSeconds(1)));
 *      .build()
 * }</pre>
 */
public class Bandwidth implements Serializable, SelfSerializable {

    private static final long serialVersionUID = 101L;

    final long capacity;
    final long initialTokens;
    final long refillPeriodNanos;
    final long refillTokens;
    final boolean refillIntervally;
    final long timeOfFirstRefillMillis;
    final boolean useAdaptiveInitialTokens;

    private Bandwidth(long capacity, long refillPeriodNanos, long refillTokens, long initialTokens, boolean refillIntervally,
              long timeOfFirstRefillMillis, boolean useAdaptiveInitialTokens) {
        this.capacity = capacity;
        this.initialTokens = initialTokens;
        this.refillPeriodNanos = refillPeriodNanos;
        this.refillTokens = refillTokens;
        this.refillIntervally = refillIntervally;
        this.timeOfFirstRefillMillis = timeOfFirstRefillMillis;
        this.useAdaptiveInitialTokens = useAdaptiveInitialTokens;
    }

    /**
     * Specifies simple limitation <tt>capacity</tt> tokens per <tt>period</tt> time window.
     *
     * @param capacity
     * @param period
     *
     * @return
     */
    public static Bandwidth simple(long capacity, Duration period) {
        Refill refill = Refill.greedy(capacity, period);
        return classic(capacity, refill);
    }

    /**
     * Specifies limitation in <a href="https://github.com/vladimir-bukhtoyarov/bucket4j/blob/1.3/doc-pages/token-bucket-brief-overview.md#token-bucket-algorithm">classic interpretation</a> of token-bucket algorithm.
     *
     * @param capacity
     * @param refill
     *
     * @return
     */
    public static Bandwidth classic(long capacity, Refill refill) {
        if (capacity <= 0) {
            throw BucketExceptions.nonPositiveCapacity(capacity);
        }
        if (refill == null) {
            throw BucketExceptions.nullBandwidthRefill();
        }
        return new Bandwidth(capacity, refill.periodNanos, refill.tokens, capacity, refill.refillIntervally, refill.timeOfFirstRefillMillis, refill.useAdaptiveInitialTokens);
    }

    /**
     * By default new created bandwidth has amount tokens that equals its capacity.
     * This method allows to replace initial tokens.
     *
     * @param initialTokens
     *
     * @return the copy of this bandwidth with new value ofof initial tokens.
     */
    public Bandwidth withInitialTokens(long initialTokens) {
        if (initialTokens < 0) {
            throw BucketExceptions.nonPositiveInitialTokens(initialTokens);
        }
        if (isIntervallyAligned() && useAdaptiveInitialTokens) {
            throw BucketExceptions.intervallyAlignedRefillWithAdaptiveInitialTokensIncompatipleWithManualSpecifiedInitialTokens();
        }
        return new Bandwidth(capacity, refillPeriodNanos, refillTokens, initialTokens, refillIntervally, timeOfFirstRefillMillis, useAdaptiveInitialTokens);
    }

    public boolean isIntervallyAligned() {
        return timeOfFirstRefillMillis != Refill.UNSPECIFIED_TIME_OF_FIRST_REFILL;
    }

    public long getCapacity() {
        return capacity;
    }

    public long getInitialTokens() {
        return initialTokens;
    }

    public long getRefillPeriodNanos() {
        return refillPeriodNanos;
    }

    public long getRefillTokens() {
        return refillTokens;
    }

    public boolean isRefillIntervally() {
        return refillIntervally;
    }

    public boolean isUseAdaptiveInitialTokens() {
        return useAdaptiveInitialTokens;
    }

    public static long getSerialVersionUID() {
        return serialVersionUID;
    }

    public long getTimeOfFirstRefillMillis() {
        return timeOfFirstRefillMillis;
    }

    @Override
    public <T> void serializeItself(SerializationBinding<T> binding, T target) throws IOException {
        binding.writeLong(target, capacity);
        binding.writeLong(target, initialTokens);
        binding.writeLong(target, refillPeriodNanos);
        binding.writeLong(target, refillTokens);
        binding.writeBoolean(target, refillIntervally);
        binding.writeLong(target, timeOfFirstRefillMillis);
        binding.writeBoolean(target, useAdaptiveInitialTokens);
    }

    public static Deserializer<Bandwidth> DESERIALIZER = new Deserializer<Bandwidth>() {
        @Override
        public <S> Bandwidth deserialize(DeserializationBinding<S> binding, S source) throws IOException {
            long capacity = binding.readLong(source);
            long initialTokens = binding.readLong(source);
            long refillPeriodNanos = binding.readLong(source);
            long refillTokens = binding.readLong(source);
            boolean refillIntervally = binding.readBoolean(source);
            long timeOfFirstRefillMillis = binding.readLong(source);
            boolean useAdaptiveInitialTokens = binding.readBoolean(source);

            return new Bandwidth(capacity, refillPeriodNanos, refillTokens, initialTokens, refillIntervally,
                    timeOfFirstRefillMillis, useAdaptiveInitialTokens);
        }
    };

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Bandwidth{");
        sb.append("capacity=").append(capacity);
        sb.append(", initialTokens=").append(initialTokens);
        sb.append(", refillPeriodNanos=").append(refillPeriodNanos);
        sb.append(", refillTokens=").append(refillTokens);
        sb.append(", refillIntervally=").append(refillIntervally);
        sb.append(", timeOfFirstRefillMillis=").append(timeOfFirstRefillMillis);
        sb.append(", useAdaptiveInitialTokens=").append(useAdaptiveInitialTokens);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Bandwidth bandwidth = (Bandwidth) o;
        return capacity == bandwidth.capacity &&
                initialTokens == bandwidth.initialTokens &&
                refillPeriodNanos == bandwidth.refillPeriodNanos &&
                refillTokens == bandwidth.refillTokens &&
                refillIntervally == bandwidth.refillIntervally &&
                timeOfFirstRefillMillis == bandwidth.timeOfFirstRefillMillis &&
                useAdaptiveInitialTokens == bandwidth.useAdaptiveInitialTokens;
    }

    @Override
    public int hashCode() {
        return Objects.hash(capacity, initialTokens, refillPeriodNanos, refillTokens, refillIntervally, timeOfFirstRefillMillis, useAdaptiveInitialTokens);
    }
}