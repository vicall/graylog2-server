/*
 * Copyright 2012-2014 TORCH GmbH
 *
 * This file is part of Graylog2.
 *
 * Graylog2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog2.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.graylog2.rest.resources.streams;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.bson.types.ObjectId;
import org.cliffc.high_scale_lib.Counter;
import org.graylog2.database.NotFoundException;
import org.graylog2.database.ValidationException;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.alarms.AlertCondition;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.plugin.streams.StreamRule;
import org.graylog2.rest.documentation.annotations.*;
import org.graylog2.rest.resources.RestResource;
import org.graylog2.rest.resources.streams.requests.CreateRequest;
import org.graylog2.rest.resources.streams.responses.StreamListResponse;
import org.graylog2.rest.resources.streams.rules.requests.CreateStreamRuleRequest;
import org.graylog2.security.RestPermissions;
import org.graylog2.shared.stats.ThroughputStats;
import org.graylog2.streams.StreamRouter;
import org.graylog2.streams.StreamRuleService;
import org.graylog2.streams.StreamService;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Lennart Koopmann <lennart@torch.sh>
 */
@RequiresAuthentication
@Api(value = "Streams", description = "Manage streams")
@Path("/streams")
public class StreamResource extends RestResource {
	private static final Logger LOG = LoggerFactory.getLogger(StreamResource.class);

    private final StreamService streamService;
    private final StreamRuleService streamRuleService;
    private final ThroughputStats throughputStats;
    private final StreamRouter streamRouter;

    @Inject
    public StreamResource(StreamService streamService,
                          StreamRuleService streamRuleService,
                          ThroughputStats throughputStats,
                          StreamRouter streamRouter) {
        this.streamService = streamService;
        this.streamRuleService = streamRuleService;
        this.throughputStats = throughputStats;
        this.streamRouter = streamRouter;
    }

    @POST @Timed
    @ApiOperation(value = "Create a stream")
    @RequiresPermissions(RestPermissions.STREAMS_CREATE)
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@ApiParam(title = "JSON body", required = true) final CreateRequest cr) throws ValidationException {
        // Create stream.
        Map<String, Object> streamData = Maps.newHashMap();
        streamData.put("title", cr.title);
        streamData.put("description", cr.description);
        streamData.put("creator_user_id", cr.creatorUserId);
        streamData.put("created_at", new DateTime(DateTimeZone.UTC));

        final Stream stream = streamService.create(streamData);
        stream.setDisabled(true);

        final String id = streamService.save(stream);

        if (cr.rules != null && cr.rules.size() > 0) {
            for (CreateStreamRuleRequest request : cr.rules) {
                Map<String, Object> streamRuleData = Maps.newHashMap();

                streamRuleData.put("type", request.type);
                streamRuleData.put("field", request.field);
                streamRuleData.put("value", request.value);
                streamRuleData.put("inverted", request.inverted);
                streamRuleData.put("stream_id", id);

                StreamRule streamRule = streamRuleService.create(streamRuleData);
                streamRuleService.save(streamRule);
            }
        }

        Map<String, Object> result = Maps.newHashMap();
        result.put("stream_id", id);

        return Response.status(Response.Status.CREATED).entity(result).build();
    }
    
    @GET @Timed
    @ApiOperation(value = "Get a list of all streams")
    @Produces(MediaType.APPLICATION_JSON)
    public StreamListResponse get() {
        StreamListResponse response = new StreamListResponse();

        response.streams = streamService.loadAll();
        response.total = response.streams.size();

        return response;
    }

    @GET @Path("/enabled") @Timed
    @ApiOperation(value = "Get a list of all enabled streams")
    @Produces(MediaType.APPLICATION_JSON)
    public String getEnabled() throws NotFoundException {
        List<Map<String, Object>> streams = Lists.newArrayList();
        for (Stream stream : streamService.loadAllEnabled()) {
            if (isPermitted(RestPermissions.STREAMS_READ, stream.getId())) {
                List<StreamRule> streamRules = streamRuleService.loadForStream(stream);
                streams.add(stream.asMap(streamRules));
            }
        }

        Map<String, Object> result = Maps.newHashMap();
        result.put("total", streams.size());
        result.put("streams", streams);

        return json(result);
    }

    @GET @Path("/{streamId}") @Timed
    @ApiOperation(value = "Get a single stream")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Stream get(@ApiParam(title = "streamId", required = true) @PathParam("streamId") String streamId) throws NotFoundException {
        if (streamId == null || streamId.isEmpty()) {
        	LOG.error("Missing streamId. Returning HTTP 400.");
        	throw new WebApplicationException(400);
        }
        checkPermission(RestPermissions.STREAMS_READ, streamId);

        Stream stream;
        stream = streamService.load(streamId);

        return stream;
    }

    @PUT @Timed
    @Path("/{streamId}")
    @ApiOperation(value = "Update a stream")
    @RequiresPermissions(RestPermissions.STREAMS_EDIT)
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Response update(@ApiParam(title = "JSON body", required = true) String body, @ApiParam(title = "streamId", required = true) @PathParam("streamId") String streamId) {
        checkPermission(RestPermissions.STREAMS_EDIT, streamId);

        CreateRequest cr;
        try {
            cr = objectMapper.readValue(body, CreateRequest.class);
        } catch(IOException e) {
            LOG.error("Error while parsing JSON", e);
            throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }

        Stream stream;
        try {
            stream = streamService.load(streamId);
        } catch (NotFoundException e) {
            throw new WebApplicationException(404);
        }

        stream.setTitle(cr.title);
        stream.setDescription(cr.description);

        try {
            streamService.save(stream);
        } catch (ValidationException e) {
            LOG.error("Validation error.", e);
            throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }

        return Response.status(Response.Status.OK).build();
    }

    @DELETE @Path("/{streamId}") @Timed
    @ApiOperation(value = "Delete a stream")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Response delete(@ApiParam(title = "streamId", required = true) @PathParam("streamId") String streamId) {
        if (streamId == null || streamId.isEmpty()) {
        	LOG.error("Missing streamId. Returning HTTP 400.");
        	throw new WebApplicationException(400);
        }
        checkPermission(RestPermissions.STREAMS_EDIT, streamId);
        try {
        	Stream stream = streamService.load(streamId);
        	streamService.destroy(stream);
        } catch (NotFoundException e) {
        	throw new WebApplicationException(404);
        }
        
        return Response.status(Response.Status.fromStatusCode(204)).build();
    }

    @POST @Path("/{streamId}/pause") @Timed
    @ApiOperation(value = "Pause a stream")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid or missing Stream id.")
    })
    public Response pause(@ApiParam(title = "streamId", required = true) @PathParam("streamId") String streamId) {
        if (streamId == null || streamId.isEmpty()) {
            LOG.error("Missing streamId. Returning HTTP 400.");
            throw new WebApplicationException(400);
        }
        checkPermission(RestPermissions.STREAMS_CHANGESTATE, streamId);

        try {
            Stream stream = streamService.load(streamId);
            streamService.pause(stream);
        } catch (NotFoundException e) {
            throw new WebApplicationException(404);
        }

        return Response.status(Response.Status.fromStatusCode(200)).build();
    }

    @POST @Path("/{streamId}/resume") @Timed
    @ApiOperation(value = "Resume a stream")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid or missing Stream id.")
    })
    public Response resume(@ApiParam(title = "streamId", required = true) @PathParam("streamId") String streamId) {
        if (streamId == null || streamId.isEmpty()) {
            LOG.error("Missing streamId. Returning HTTP 400.");
            throw new WebApplicationException(400);
        }
        checkPermission(RestPermissions.STREAMS_CHANGESTATE, streamId);

        try {
            Stream stream = streamService.load(streamId);
            streamService.resume(stream);
        } catch (NotFoundException e) {
            throw new WebApplicationException(404);
        }

        return Response.status(Response.Status.fromStatusCode(200)).build();
    }

    @POST @Path("/{streamId}/testMatch") @Timed
    @ApiOperation(value = "Test matching of a stream against a supplied message")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid or missing Stream id.")
    })
    public String testMatch(@ApiParam(title = "streamId", required = true) @PathParam("streamId") String streamId, @ApiParam(title = "JSON body", required = true) String body) {
        if (body == null || body.isEmpty()) {
            LOG.error("Missing parameters. Returning HTTP 400.");
            throw new WebApplicationException(400);
        }
        checkPermission(RestPermissions.STREAMS_READ, streamId);

        Map<String, Map<String, Object>> serialisedMessage;
        try {
            serialisedMessage = objectMapper.readValue(body, new TypeReference<Map<String, Map<String, Object>>>() {});
        } catch (IOException e) {
            LOG.error("Received invalid JSON body. Returning HTTP 400.");
            throw new BadRequestException();
        }

        if (serialisedMessage.get("message") == null) {
            LOG.error("Received invalid JSON body. Returning HTTP 400.");
            throw new WebApplicationException(400);
        }

        Stream stream = fetchStream(streamId);
        Message message = new Message(serialisedMessage.get("message"));

        Map<StreamRule, Boolean> ruleMatches = streamRouter.getRuleMatches(stream, message);
        Map<String, Boolean> rules = Maps.newHashMap();

        for (StreamRule ruleMatch : ruleMatches.keySet()) {
            rules.put(ruleMatch.getId(), ruleMatches.get(ruleMatch));
        }

        Map<String, Object> result = Maps.newHashMap();
        result.put("matches", streamRouter.doesStreamMatch(ruleMatches));
        result.put("rules", rules);

        return json(result);
    }

    @POST @Path("/{streamId}/clone") @Timed
    @ApiOperation(value = "Clone a stream")
    @RequiresPermissions(RestPermissions.STREAMS_CREATE)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stream not found."),
            @ApiResponse(code = 400, message = "Invalid or missing Stream id.")
    })
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cloneStream(@ApiParam(title = "streamId", required = true) @PathParam("streamId") String streamId,
                          @ApiParam(title = "JSON body", required = true) CreateRequest cr) {
        checkPermission(RestPermissions.STREAMS_CREATE);
        checkPermission(RestPermissions.STREAMS_READ, streamId);

        Stream sourceStream = fetchStream(streamId);

        // Create stream.
        Map<String, Object> streamData = Maps.newHashMap();
        streamData.put("title", cr.title);
        streamData.put("description", cr.description);
        streamData.put("creator_user_id", cr.creatorUserId);
        streamData.put("created_at", new DateTime(DateTimeZone.UTC));

        Stream stream = streamService.create(streamData);
        streamService.pause(stream);
        String id;
        try {
            streamService.save(stream);
            id = stream.getId();
        } catch (ValidationException e) {
            LOG.error("Validation error.", e);
            throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }

        List<StreamRule> sourceStreamRules;

        try {
            sourceStreamRules = streamRuleService.loadForStream(sourceStream);
        } catch (NotFoundException e) {
            sourceStreamRules = Lists.newArrayList();
        }

        if (sourceStreamRules.size() > 0) {
            for (StreamRule streamRule : sourceStreamRules) {
                Map<String, Object> streamRuleData = Maps.newHashMap();

                streamRuleData.put("type", streamRule.getType().toInteger());
                streamRuleData.put("field", streamRule.getField());
                streamRuleData.put("value", streamRule.getValue());
                streamRuleData.put("inverted", streamRule.getInverted());
                streamRuleData.put("stream_id", new ObjectId(id));

                StreamRule newStreamRule = streamRuleService.create(streamRuleData);
                try {
                    streamRuleService.save(newStreamRule);
                } catch (ValidationException e) {
                    LOG.error("Validation error while trying to clone a stream rule: ", e);
                    throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
                }
            }
        }

        if (streamService.getAlertConditions(sourceStream).size() > 0) {
            for (AlertCondition alertCondition : streamService.getAlertConditions(sourceStream)) {
                try {
                    streamService.addAlertCondition(stream, alertCondition);
                } catch (ValidationException e) {
                    LOG.error("Validation error while trying to clone an alert condition: ", e);
                    throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
                }
            }
        }

        Map<String, Object> result = Maps.newHashMap();
        result.put("stream_id", id);

        return Response.status(Response.Status.CREATED).entity(json(result)).build();
    }

    @GET @Timed
    @Path("/{streamId}/throughput")
    @ApiOperation(value = "Current throughput of this stream on this node in messages per second")
    @Produces(MediaType.APPLICATION_JSON)
    public Response oneStreamThroughput(@ApiParam(title="streamId", required = true) @PathParam("streamId") String streamId) {
        Map<String, Long> result = Maps.newHashMap();
        result.put("throughput", 0L);

        final HashMap<String,Counter> currentStreamThroughput = throughputStats.getCurrentStreamThroughput();
        if (currentStreamThroughput != null) {
            final Counter counter = currentStreamThroughput.get(streamId);
            if (counter != null && isPermitted(RestPermissions.STREAMS_READ, streamId)) {
                result.put("throughput", counter.get());
            }
        }
        return Response.ok().entity(json(result)).build();
    }

    @GET @Timed
    @Path("/throughput")
    @ApiOperation("Current throughput of all visible streams on this node in messages per second")
    @Produces(MediaType.APPLICATION_JSON)
    public Response streamThroughput() {
        Map<String, Map<String, Long>> result = Maps.newHashMap();
        final Map<String, Long> perStream = Maps.newHashMap();
        result.put("throughput", perStream);

        final HashMap<String,Counter> currentStreamThroughput = throughputStats.getCurrentStreamThroughput();
        if (currentStreamThroughput != null) {
            for (Map.Entry<String, Counter> entry : currentStreamThroughput.entrySet()) {
                if (entry.getValue() != null && isPermitted(RestPermissions.STREAMS_READ, entry.getKey())) {
                    perStream.put(entry.getKey(), entry.getValue().get());
                }
            }

        }
        return Response.ok().entity(json(result)).build();
    }

    protected Stream fetchStream(String streamId) {
        if (streamId == null || streamId.isEmpty()) {
            LOG.error("Missing streamId. Returning HTTP 400.");
            throw new WebApplicationException(400);
        }

        try {
            Stream stream = streamService.load(streamId);
            return stream;
        } catch (NotFoundException e) {
            throw new WebApplicationException(404);
        }
    }
}
