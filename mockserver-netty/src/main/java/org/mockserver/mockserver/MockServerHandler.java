package org.mockserver.mockserver;

import com.google.common.net.MediaType;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import org.mockserver.mock.HttpStateHandler;
import org.mockserver.client.serialization.*;
import org.mockserver.cors.CORSHeaders;
import org.mockserver.filters.RequestLogFilter;
import org.mockserver.logging.LogFormatter;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.MockServerMatcher;
import org.mockserver.mock.action.ActionHandler;
import org.mockserver.mockserver.callback.ExpectationCallbackResponse;
import org.mockserver.mockserver.callback.WebSocketClientRegistry;
import org.mockserver.model.*;
import org.mockserver.socket.KeyAndCertificateFactory;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.BindException;
import java.util.List;

import static com.google.common.net.MediaType.JSON_UTF_8;
import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.ConfigurationProperties.enableCORSForAPI;
import static org.mockserver.configuration.ConfigurationProperties.enableCORSForAllResponses;
import static org.mockserver.exception.ExceptionHandler.closeOnFlush;
import static org.mockserver.exception.ExceptionHandler.shouldIgnoreException;
import static org.mockserver.model.ConnectionOptions.connectionOptions;
import static org.mockserver.model.ConnectionOptions.isFalseOrNull;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.PortBinding.portBinding;

@ChannelHandler.Sharable
public class MockServerHandler extends SimpleChannelInboundHandler<HttpRequest> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final LogFormatter logFormatter = new LogFormatter(logger);
    // mockserver
    private MockServer server;
    private RequestLogFilter requestLogFilter;
    private MockServerMatcher mockServerMatcher;
    private WebSocketClientRegistry webSocketClientRegistry;
    private ActionHandler actionHandler;
    private HttpStateHandler httpStateHandler;
    // serializers
    private ExpectationSerializer expectationSerializer = new ExpectationSerializer();
    private PortBindingSerializer portBindingSerializer = new PortBindingSerializer();
    private VerificationSerializer verificationSerializer = new VerificationSerializer();
    private VerificationSequenceSerializer verificationSequenceSerializer = new VerificationSequenceSerializer();
    // CORS
    private CORSHeaders addCORSHeaders = new CORSHeaders();

    public MockServerHandler(MockServer server, MockServerMatcher mockServerMatcher, WebSocketClientRegistry webSocketClientRegistry, RequestLogFilter requestLogFilter) {
        super(false);
        this.server = server;
        this.requestLogFilter = requestLogFilter;
        this.mockServerMatcher = mockServerMatcher;
        this.webSocketClientRegistry = webSocketClientRegistry;
        actionHandler = new ActionHandler(requestLogFilter);
        httpStateHandler = new HttpStateHandler(requestLogFilter, null, mockServerMatcher);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final HttpRequest request) {

        try {

            logFormatter.traceLog("received request:{}" + NEW_LINE, request);

            if ((enableCORSForAPI() || enableCORSForAllResponses()) && request.getMethod().getValue().equals("OPTIONS") && !request.getFirstHeader("Origin").isEmpty()) {

                writeResponse(ctx, request, OK);

            } else if (request.matches("PUT", "/status")) {

                writeResponse(ctx, request, OK, portBindingSerializer.serialize(portBinding(server.getPorts())), "application/json");

            } else if (request.matches("PUT", "/bind")) {

                PortBinding requestedPortBindings = portBindingSerializer.deserialize(request.getBodyAsString());
                try {
                    List<Integer> actualPortBindings = server.bindToPorts(requestedPortBindings.getPorts());
                    writeResponse(ctx, request, OK, portBindingSerializer.serialize(portBinding(actualPortBindings)), "application/json");
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof BindException) {
                        writeResponse(ctx, request, BAD_REQUEST, e.getMessage() + " port already in use", MediaType.create("text", "plain").toString());
                    } else {
                        throw e;
                    }
                }

            } else if (request.matches("PUT", "/expectation")) {

                for (Expectation expectation : expectationSerializer.deserializeArray(request.getBodyAsString())) {
                    KeyAndCertificateFactory.addSubjectAlternativeName(expectation.getHttpRequest().getFirstHeader(HOST.toString()));
                    mockServerMatcher
                            .when(expectation.getHttpRequest(), expectation.getTimes(), expectation.getTimeToLive())
                            .thenRespond(expectation.getHttpResponse())
                            .thenRespond(expectation.getHttpResponseTemplate())
                            .thenForward(expectation.getHttpForward())
                            .thenError(expectation.getHttpError())
                            .thenCallback(expectation.getHttpClassCallback())
                            .thenCallback(expectation.getHttpObjectCallback());
                    logFormatter.infoLog("creating expectation:{}", expectation);
                }
                writeResponse(ctx, request, CREATED);

            } else if (request.matches("PUT", "/clear")) {

                httpStateHandler.clear(request);
                writeResponse(ctx, request, OK);

            } else if (request.matches("PUT", "/reset")) {

                httpStateHandler.reset();
                writeResponse(ctx, request, OK);

            } else if (request.matches("PUT", "/dumpToLog")) {

                httpStateHandler.dumpExpectationsToLog(request);
                writeResponse(ctx, request, OK);

            } else if (request.matches("PUT", "/retrieve")) {

                writeResponse(ctx, request, OK, httpStateHandler.retrieve(request),
                        JSON_UTF_8.toString().replace(request.hasQueryStringParameter("format", "java") ? "json" : "", "java")
                );

            } else if (request.matches("PUT", "/verify")) {

                Verification verification = verificationSerializer.deserialize(request.getBodyAsString());
                String result = requestLogFilter.verify(verification);
                verifyResponse(ctx, request, result);
                logFormatter.infoLog("verifying requests that match:{}", verification);

            } else if (request.matches("PUT", "/verifySequence")) {

                VerificationSequence verificationSequence = verificationSequenceSerializer.deserialize(request.getBodyAsString());
                String result = requestLogFilter.verify(verificationSequence);
                verifyResponse(ctx, request, result);
                logFormatter.infoLog("verifying sequence that match:{}", verificationSequence);

            } else if (request.matches("PUT", "/stop")) {

                ctx.writeAndFlush(response().withStatusCode(OK.code()));
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        server.stop();
                    }
                }).start();

            } else {

                Action action = mockServerMatcher.retrieveAction(request);
                if (action instanceof HttpError) {
                    HttpError httpError = ((HttpError) action).applyDelay();
                    requestLogFilter.onRequest(request);
                    if (httpError.getResponseBytes() != null) {
                        // write byte directly by skipping over HTTP codec
                        ChannelHandlerContext httpCodecContext = ctx.pipeline().context(HttpServerCodec.class);
                        if (httpCodecContext != null) {
                            httpCodecContext.writeAndFlush(Unpooled.wrappedBuffer(httpError.getResponseBytes())).awaitUninterruptibly();
                        }
                    }
                    if (httpError.getDropConnection()) {
                        ctx.close();
                    }
                } else if (action instanceof HttpObjectCallback) {
                    String clientId = ((HttpObjectCallback) action).getClientId();
                    webSocketClientRegistry.registerCallbackResponseHandler(clientId, new ExpectationCallbackResponse() {
                        @Override
                        public void handle(HttpResponse response) {
                            requestLogFilter.onResponse(request, response);
                            writeResponse(ctx, request, response.withConnectionOptions(connectionOptions().withCloseSocket(true)));
                            logFormatter.infoLog("returning response:{}" + NEW_LINE + " for request:{}", response, request);
                        }
                    });
                    requestLogFilter.onRequest(request);
                    webSocketClientRegistry.sendClientMessage(clientId, request);
                } else {
                    HttpResponse response = actionHandler.processAction(action, request);
                    writeResponse(ctx, request, response);
                    logFormatter.infoLog("returning response:{}" + NEW_LINE + " for request:{}", response, request);
                }

            }
        } catch (IllegalArgumentException iae) {
            logger.error("Exception processing " + request, iae);
            // send request without API CORS headers
            writeResponse(ctx, request, BAD_REQUEST, iae.getMessage(), MediaType.create("text", "plain").toString());
        } catch (Exception e) {
            logger.error("Exception processing " + request, e);
            writeResponse(ctx, request, response().withStatusCode(BAD_REQUEST.code()).withBody(e.getMessage()));
        }
    }

    private void verifyResponse(ChannelHandlerContext ctx, HttpRequest request, String result) {
        if (result.isEmpty()) {
            writeResponse(ctx, request, ACCEPTED);
        } else {
            writeResponse(ctx, request, NOT_ACCEPTABLE, result, MediaType.create("text", "plain").toString());
        }
    }

    private void writeResponse(ChannelHandlerContext ctx, HttpRequest request, HttpResponseStatus responseStatus) {
        writeResponse(ctx, request, responseStatus, "", "application/json");
    }

    private void writeResponse(ChannelHandlerContext ctx, HttpRequest request, HttpResponseStatus responseStatus, String body, String contentType) {
        HttpResponse response = response()
                .withStatusCode(responseStatus.code())
                .withBody(body);
        if (body != null && !body.isEmpty()) {
            response.updateHeader(header(CONTENT_TYPE.toString(), contentType + "; charset=utf-8"));
        }
        if (enableCORSForAPI()) {
            addCORSHeaders.addCORSHeaders(response);
        }
        writeResponse(ctx, request, response);
    }

    private void writeResponse(ChannelHandlerContext ctx, HttpRequest request, HttpResponse response) {
        if (response == null) {
            response = notFoundResponse();
        }
        if (enableCORSForAllResponses()) {
            addCORSHeaders.addCORSHeaders(response);
        }

        addConnectionHeader(request, response);

        writeAndCloseSocket(ctx, request, response);
    }

    private void addConnectionHeader(HttpRequest request, HttpResponse response) {
        ConnectionOptions connectionOptions = response.getConnectionOptions();
        if (connectionOptions != null && connectionOptions.getKeepAliveOverride() != null) {
            if (connectionOptions.getKeepAliveOverride()) {
                response.updateHeader(header(CONNECTION.toString(), KEEP_ALIVE.toString()));
            } else {
                response.updateHeader(header(CONNECTION.toString(), CLOSE.toString()));
            }
        } else if (connectionOptions == null || isFalseOrNull(connectionOptions.getSuppressConnectionHeader())) {
            if (request.isKeepAlive() != null && request.isKeepAlive()
                    && (connectionOptions == null || isFalseOrNull(connectionOptions.getCloseSocket()))) {
                response.updateHeader(header(CONNECTION.toString(), KEEP_ALIVE.toString()));
            } else {
                response.updateHeader(header(CONNECTION.toString(), CLOSE.toString()));
            }
        }
    }

    private void writeAndCloseSocket(ChannelHandlerContext ctx, HttpRequest request, HttpResponse response) {
        boolean closeChannel;

        ConnectionOptions connectionOptions = response.getConnectionOptions();
        if (connectionOptions != null && connectionOptions.getCloseSocket() != null) {
            closeChannel = connectionOptions.getCloseSocket();
        } else {
            closeChannel = !(request.isKeepAlive() != null && request.isKeepAlive());
        }

        if (closeChannel) {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.write(response);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!shouldIgnoreException(cause)) {
            logger.warn("Exception caught by " + server.getClass() + " handler -> closing pipeline " + ctx.channel(), cause);
        }
        closeOnFlush(ctx.channel());
    }
}
