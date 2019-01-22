/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Candidates for network selection
 */
public class WifiCandidates {
    private static final String TAG = "WifiCandidates";

    WifiCandidates(WifiScoreCard wifiScoreCard) {
        mWifiScoreCard = wifiScoreCard;
    }
    private final WifiScoreCard mWifiScoreCard;

    /**
     * Represents a connectable candidate
     */
    public static class Candidate {
        public final Key key;                   // SSID/sectype/BSSID/configId
        public final ScanDetail scanDetail;
        public final WifiConfiguration config;
        public final int evaluatorIndex;        // First evaluator to nominate this config
        public final int evaluatorScore;        // Score provided by first nominating evaluator
        private WifiScoreCard.PerBssid mPerBssid;

        public Candidate(Key key,
                         ScanDetail scanDetail,
                         WifiConfiguration config,
                         int evaluatorIndex,
                         int evaluatorScore) {
            this.key = key;
            this.scanDetail = scanDetail;
            this.config = config;
            this.evaluatorIndex = evaluatorIndex;
            this.evaluatorScore = evaluatorScore;
        }

        public int getScanRssi() {
            return scanDetail.getScanResult().level;
        }

        public int getFrequency() {
            return scanDetail.getScanResult().frequency;
        }

        /**
         * Accesses statistical information from the score card
         */
        public WifiScoreCardProto.Signal getEventStatistics(WifiScoreCardProto.Event event) {
            return null;
        }

    }

    /**
     * Represents a scoring function
     */
    public interface CandidateScorer {
        /**
         * The scorer's name, and perhaps important parameterization/version.
         */
        String getIdentifier();

        /**
         * Calculates the score for a group of candidates that belong
         * to the same network.
         */
        @Nullable ScoredCandidate scoreCandidates(@NonNull Collection<Candidate> group);

        /**
         * Returns true if the legacy user connect choice logic should be used.
         *
         * @returns false to disable the legacy logic
         */
        boolean userConnectChoiceOverrideWanted();
    }

    /**
     * Represents a candidate with a real-valued score, along with an error estimate.
     *
     * Larger values reflect more desirable candidates. The range is arbitrary,
     * because scores generated by different sources are not compared with each
     * other.
     *
     * The error estimate is on the same scale as the value, and should
     * always be strictly positive. For instance, it might be the standard deviation.
     */
    public static class ScoredCandidate {
        public final double value;
        public final double err;
        public final Key candidateKey;
        public ScoredCandidate(double value, double err, Candidate candidate) {
            this.value = value;
            this.err = err;
            this.candidateKey = (candidate == null) ? null : candidate.key;
        }
        /**
         * Represents no score
         */
        public static final ScoredCandidate NONE =
                new ScoredCandidate(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, null);
    }

    /**
     * The key used for tracking candidates, consisting of SSID, security type, BSSID, and network
     * configuration id.
     */
    public static class Key {
        public final ScanResultMatchInfo matchInfo; // Contains the SSID and security type
        public final MacAddress bssid;
        public final int networkId;                 // network configuration id

        public Key(ScanResultMatchInfo matchInfo,
                   MacAddress bssid,
                   int networkId) {
            this.matchInfo = matchInfo;
            this.bssid = bssid;
            this.networkId = networkId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return (this.matchInfo.equals(that.matchInfo)
                    && this.bssid.equals(that.bssid)
                    && this.networkId == that.networkId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(matchInfo, bssid, networkId);
        }
    }

    private final Map<Key, Candidate> mCandidates = new ArrayMap<>();

    /**
     * Adds a new candidate
     *
     * @returns true if added or replaced, false otherwise
     */
    public boolean add(ScanDetail scanDetail,
                    WifiConfiguration config,
                    int evaluatorIndex,
                    int evaluatorScore) {
        if (config == null) return failure();
        if (scanDetail == null) return failure();
        ScanResult scanResult = scanDetail.getScanResult();
        if (scanResult == null) return failure();
        MacAddress bssid;
        try {
            bssid = MacAddress.fromString(scanResult.BSSID);
        } catch (RuntimeException e) {
            return failWithException(e);
        }
        ScanResultMatchInfo key1 = ScanResultMatchInfo.fromWifiConfiguration(config);
        ScanResultMatchInfo key2 = ScanResultMatchInfo.fromScanResult(scanResult);
        if (!key1.equals(key2)) return failure(key1, key2);
        Key key = new Key(key1, bssid, config.networkId);
        Candidate old = mCandidates.get(key);
        if (old != null) {
            // check if we want to replace this old candidate
            if (evaluatorIndex < old.evaluatorIndex) return failure();
            if (evaluatorIndex > old.evaluatorIndex) return false;
            if (evaluatorScore <= old.evaluatorScore) return false;
            remove(old);
        }
        Candidate candidate = new Candidate(key,
                scanDetail, config, evaluatorIndex, evaluatorScore);
        mCandidates.put(key, candidate);
        return true;
    }

    /**
     * Removes a candidate
     * @returns true if the candidate was successfully removed
     */
    public boolean remove(Candidate candidate) {
        if (candidate == null) return failure();
        return mCandidates.remove(candidate.key, candidate);
    }

    /**
     * Returns the number of candidates (at the BSSID level)
     */
    public int size() {
        return mCandidates.size();
    }

    /**
     * Returns the candidates, grouped by network.
     */
    public Collection<Collection<Candidate>> getGroupedCandidates() {
        Map<Integer, Collection<Candidate>> candidatesForNetworkId = new ArrayMap<>();
        for (Candidate candidate : mCandidates.values()) {
            Collection<Candidate> cc = candidatesForNetworkId.get(candidate.key.networkId);
            if (cc == null) {
                cc = new ArrayList<>(2); // Guess 2 bssids per network
                candidatesForNetworkId.put(candidate.key.networkId, cc);
            }
            cc.add(candidate);
        }
        return candidatesForNetworkId.values();
    }

    /**
     * Make a choice from among the candidates, using the provided scorer.
     *
     * @returns the chosen scored candidate, or ScoredCandidate.NONE.
     */
    public @NonNull ScoredCandidate choose(@NonNull CandidateScorer candidateScorer) {
        ScoredCandidate choice = ScoredCandidate.NONE;
        for (Collection<Candidate> group : getGroupedCandidates()) {
            ScoredCandidate scoredCandidate = candidateScorer.scoreCandidates(group);
            if (scoredCandidate != null && scoredCandidate.value > choice.value) {
                choice = scoredCandidate;
            }
        }
        return choice;
    }

    /**
     * After a failure indication is returned, this may be used to get details.
     */
    public RuntimeException getLastFault() {
        return mLastFault;
    }

    /**
     * Returns the number of faults we have seen
     */
    public int getFaultCount() {
        return mFaultCount;
    }

    /**
     * Clears any recorded faults
     */
    public void clearFaults() {
        mLastFault = null;
        mFaultCount = 0;
    }

    /**
     * Controls whether to immediately raise an exception on a failure
     */
    public WifiCandidates setPicky(boolean picky) {
        mPicky = picky;
        return this;
    }

    /**
     * Records details about a failure
     *
     * This captures a stack trace, so don't bother to construct a string message, just
     * supply any culprits (convertible to strings) that might aid diagnosis.
     *
     * @returns false
     * @throws RuntimeException (if in picky mode)
     */
    private boolean failure(Object... culprits) {
        StringJoiner joiner = new StringJoiner(",");
        for (Object c : culprits) {
            joiner.add("" + c);
        }
        return failWithException(new IllegalArgumentException(joiner.toString()));
    }

    /**
     * As above, if we already have an exception.
     */
    private boolean failWithException(RuntimeException e) {
        mLastFault = e;
        mFaultCount++;
        if (mPicky) {
            throw e;
        }
        return false;
    }

    private boolean mPicky = false;
    private RuntimeException mLastFault = null;
    private int mFaultCount = 0;

}
