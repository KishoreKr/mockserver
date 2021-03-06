package org.mockserver.mock;

import com.google.common.annotations.VisibleForTesting;
import org.mockserver.client.serialization.Base64Converter;
import org.mockserver.client.serialization.ExpectationSerializer;
import org.mockserver.client.serialization.java.ExpectationToJavaSerializer;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.MatcherBuilder;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.Action;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author jamesdbloom
 */
public class MockServerMatcher extends ObjectWithReflectiveEqualsHashCodeToString {

    protected final List<Expectation> expectations = Collections.synchronizedList(new ArrayList<Expectation>());
    private Logger requestLogger = LoggerFactory.getLogger("REQUEST");

    public Expectation when(HttpRequest httpRequest) {
        return when(httpRequest, Times.unlimited(), TimeToLive.unlimited());
    }

    public Expectation when(final HttpRequest httpRequest, Times times, TimeToLive timeToLive) {
        Expectation expectation;
        if (times.isUnlimited()) {
            Collection<Expectation> existingExpectationsWithMatchingRequest = new ArrayList<Expectation>();
            for (Expectation potentialExpectation : new ArrayList<Expectation>(this.expectations)) {
                if (potentialExpectation.contains(httpRequest)) {
                    existingExpectationsWithMatchingRequest.add(potentialExpectation);
                }
            }
            if (!existingExpectationsWithMatchingRequest.isEmpty()) {
                for (Expectation existingExpectation : existingExpectationsWithMatchingRequest) {
                    existingExpectation.setNotUnlimitedResponses();
                }
                expectation = new Expectation(httpRequest, times, timeToLive);
            } else {
                expectation = new Expectation(httpRequest, Times.unlimited(), timeToLive);
            }
        } else {
            expectation = new Expectation(httpRequest, times, timeToLive);
        }
        this.expectations.add(expectation);
        return expectation;
    }

    public Action retrieveAction(HttpRequest httpRequest) {
        for (Expectation expectation : new ArrayList<>(this.expectations)) {
            if (expectation.matches(httpRequest)) {
                expectation.decrementRemainingMatches();
                if (!expectation.hasRemainingMatches()) {
                    if (this.expectations.contains(expectation)) {
                        this.expectations.remove(expectation);
                    }
                }
                return expectation.getAction();
            } else if (!expectation.isStillAlive()) {
                if (this.expectations.contains(expectation)) {
                    this.expectations.remove(expectation);
                }
            }
        }
        return null;
    }

    public void clear(HttpRequest httpRequest) {
        if (httpRequest != null) {
            HttpRequestMatcher httpRequestMatcher = new MatcherBuilder().transformsToMatcher(httpRequest);
            for (Expectation expectation : new ArrayList<Expectation>(this.expectations)) {
                if (httpRequestMatcher.matches(expectation.getHttpRequest(), true)) {
                    if (this.expectations.contains(expectation)) {
                        this.expectations.remove(expectation);
                    }
                }
            }
        } else {
            reset();
        }
    }

    public void reset() {
        this.expectations.clear();
    }

    public void dumpToLog(HttpRequest httpRequest, boolean asJava) {
        ExpectationSerializer expectationSerializer = new ExpectationSerializer();
        ExpectationToJavaSerializer expectationToJavaSerializer = new ExpectationToJavaSerializer();
        if (httpRequest != null) {
            for (Expectation expectation : new ArrayList<>(this.expectations)) {
                if (expectation.matches(httpRequest)) {
                    if (asJava) {
                        requestLogger.info(expectationToJavaSerializer.serializeAsJava(0, expectation));
                    } else {
                        requestLogger.info(expectationSerializer.serialize(expectation));
                    }
                }
            }
        } else {
            for (Expectation expectation : new ArrayList<>(this.expectations)) {
                if (asJava) {
                    requestLogger.info(expectationToJavaSerializer.serializeAsJava(0, expectation));
                } else {
                    requestLogger.info(expectationSerializer.serialize(expectation));
                }
            }
        }
    }

    public List<Expectation> retrieveExpectations(HttpRequest httpRequest) {
        List<Expectation> expectations = new ArrayList<Expectation>();
        if (httpRequest != null) {
            for (Expectation expectation : new ArrayList<>(this.expectations)) {
                if (expectation.matches(httpRequest)) {
                    expectations.add(expectation);
                }
            }
        } else {
            expectations.addAll(this.expectations);
        }
        return expectations;
    }
}
