/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.alerts.types;

import com.google.common.collect.Lists;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import org.graylog2.alerts.AbstractAlertCondition;
import org.graylog2.indexer.InvalidRangeFormatException;
import org.graylog2.indexer.results.CountResult;
import org.graylog2.indexer.results.ResultMessage;
import org.graylog2.indexer.results.SearchResult;
import org.graylog2.indexer.searches.Searches;
import org.graylog2.indexer.searches.Sorting;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.MessageSummary;
import org.graylog2.plugin.Tools;
import org.graylog2.plugin.alarms.AlertCondition;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.DropdownField;
import org.graylog2.plugin.configuration.fields.NumberField;
import org.graylog2.plugin.indexer.searches.timeranges.AbsoluteRange;
import org.graylog2.plugin.indexer.searches.timeranges.InvalidRangeParametersException;
import org.graylog2.plugin.indexer.searches.timeranges.RelativeRange;
import org.graylog2.plugin.streams.Stream;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class MessageCountAlertCondition extends AbstractAlertCondition {
    private static final Logger LOG = LoggerFactory.getLogger(MessageCountAlertCondition.class);

    enum ThresholdType {

        MORE("more than"),
        LESS("less than");

        private final String description;

        ThresholdType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public interface Factory extends AlertCondition.Factory {
        MessageCountAlertCondition create(Stream stream,
                                          @Assisted("id") String id,
                                          DateTime createdAt,
                                          @Assisted("userid") String creatorUserId,
                                          Map<String, Object> parameters,
                                          @Assisted("title") @Nullable String title);
        Config config();
        Descriptor descriptor();
    }

    public static class Config implements AlertCondition.Config {
        public Config() {
        }

        @Override
        public ConfigurationRequest getRequestedConfiguration() {
            return ConfigurationRequest.createWithFields(
                new NumberField("time", "Time Range", 0, "Time span in seconds to check", ConfigurationField.Optional.NOT_OPTIONAL),
                new NumberField("threshold", "Threshold", 0.0, "Value which triggers an alert if crossed", ConfigurationField.Optional.NOT_OPTIONAL),
                new DropdownField(
                    "threshold_type",
                    "Threshold Type",
                    MessageCountAlertCondition.ThresholdType.MORE.toString(),
                    Arrays.stream(MessageCountAlertCondition.ThresholdType.values()).collect(Collectors.toMap(Enum::toString, ThresholdType::getDescription)),
                    ConfigurationField.Optional.NOT_OPTIONAL)
            ).addFields(AbstractAlertCondition.getDefaultConfigurationFields());
        }
    }

    public static class Descriptor extends AlertCondition.Descriptor {
        public Descriptor() {
            super(
                "Message Count Alert Condition",
                "https://www.graylog.org/",
                "This condition is triggered when the number of messages in a defined time interval is higher or lower a defined threshold."
            );
        }
    }

    private final int time;
    private final ThresholdType thresholdType;
    private final int threshold;
    private final Searches searches;

    @AssistedInject
    public MessageCountAlertCondition(Searches searches,
                                      @Assisted Stream stream,
                                      @Nullable @Assisted("id") String id,
                                      @Assisted DateTime createdAt,
                                      @Assisted("userid") String creatorUserId,
                                      @Assisted Map<String, Object> parameters,
                                      @Nullable @Assisted("title") String title) {
        super(stream, id, Type.MESSAGE_COUNT.toString(), createdAt, creatorUserId, parameters, title);

        this.searches = searches;
        this.time = getNumber(parameters.get("time")).orElse(0).intValue();
        this.thresholdType = ThresholdType.valueOf(((String) parameters.get("threshold_type")).toUpperCase(Locale.ENGLISH));
        this.threshold = getNumber(parameters.get("threshold")).orElse(0).intValue();
    }

    @Override
    public String getDescription() {
        return "time: " + time
            + ", threshold_type: " + thresholdType.toString().toLowerCase(Locale.ENGLISH)
            + ", threshold: " + threshold
            + ", grace: " + grace;
    }

    @Override
    public CheckResult runCheck() {
        try {
            // Create an absolute range from the relative range to make sure it doesn't change during the two
            // search requests. (count and find messages)
            // This is needed because the RelativeRange computes the range from NOW on every invocation of getFrom() and
            // getTo().
            // See: https://github.com/Graylog2/graylog2-server/issues/2382
            final RelativeRange relativeRange = RelativeRange.create(time * 60);
            final AbsoluteRange range = AbsoluteRange.create(relativeRange.getFrom(), relativeRange.getTo());

            final String filter = "streams:" + stream.getId();
            final CountResult result = searches.count("*", range, filter);
            final long count = result.count();

            LOG.debug("Alert check <{}> result: [{}]", id, count);

            final boolean triggered;
            switch (thresholdType) {
                case MORE:
                    triggered = count > threshold;
                    break;
                case LESS:
                    triggered = count < threshold;
                    break;
                default:
                    triggered = false;
            }

            if (triggered) {
                final List<MessageSummary> summaries = Lists.newArrayList();
                if (getBacklog() > 0) {
                    final SearchResult backlogResult = searches.search("*", filter,
                        range, getBacklog(), 0, new Sorting("timestamp", Sorting.Direction.DESC));
                    for (ResultMessage resultMessage : backlogResult.getResults()) {
                        final Message msg = resultMessage.getMessage();
                        summaries.add(new MessageSummary(resultMessage.getIndex(), msg));
                    }
                }

                final String resultDescription = "Stream had " + count + " messages in the last " + time
                    + " minutes with trigger condition " + thresholdType.toString().toLowerCase(Locale.ENGLISH)
                    + " than " + threshold + " messages. " + "(Current grace time: " + grace + " minutes)";
                return new CheckResult(true, this, resultDescription, Tools.nowUTC(), summaries);
            } else {
                return new NegativeCheckResult();
            }
        } catch (InvalidRangeParametersException e) {
            // cannot happen lol
            LOG.error("Invalid timerange.", e);
            return null;
        } catch (InvalidRangeFormatException e) {
            // lol same here
            LOG.error("Invalid timerange format.", e);
            return null;
        }
    }
}
