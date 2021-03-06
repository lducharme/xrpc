/*
 * Copyright 2018 Nordstrom, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nordstrom.xrpc.server;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.json.MetricsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nordstrom.xrpc.XConfig;
import com.nordstrom.xrpc.server.tls.Tls;
import com.typesafe.config.Config;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

/** An xprc server. */
@Slf4j
public class Server {
  private final XConfig config;
  private final Routes routes;
  private final Tls tls;
  private final XrpcConnectionContext.Builder contextBuilder;

  private final MetricRegistry metricRegistry = new MetricRegistry();

  @Getter private Channel channel;
  @Getter private final HealthCheckRegistry healthCheckRegistry;

  /**
   * Build a server handling the given routes. Routes configuration will be captured when the server
   * starts with listenAndServe (they may be modified after calling the constructor).
   *
   * @param config overrides for the default configuration values
   */
  public Server(Config config, Routes routes) {
    this(new XConfig(config), routes);
  }

  /**
   * Build a server handling the given routes. Routes configuration will be captured when the server
   * starts with listenAndServe (they may be modified after calling the constructor).
   *
   * @param config the configuration for the server
   */
  public Server(XConfig config, Routes routes) {
    this.config = config;
    this.routes = routes;
    this.tls = new Tls(config.cert(), config.key());
    this.healthCheckRegistry = new HealthCheckRegistry(config.asyncHealthCheckThreadCount());

    if (config.serveAdminRoutes()) {
      addAdminRoutes(routes);
    }

    this.contextBuilder =
        XrpcConnectionContext.builder()
            .requestMeter(metricRegistry.meter("requests"))
            .mapper(new ObjectMapper());
    addResponseCodeMeters(contextBuilder);
  }

  /** Adds a meter for all HTTP response codes to the given XrpcConnectionContext. */
  private void addResponseCodeMeters(XrpcConnectionContext.Builder contextBuilder) {
    Map<HttpResponseStatus, String> namesByCode = new HashMap<>();
    namesByCode.put(HttpResponseStatus.OK, "ok");
    namesByCode.put(HttpResponseStatus.CREATED, "created");
    namesByCode.put(HttpResponseStatus.ACCEPTED, "accepted");
    namesByCode.put(HttpResponseStatus.NO_CONTENT, "noContent");
    namesByCode.put(HttpResponseStatus.BAD_REQUEST, "badRequest");
    namesByCode.put(HttpResponseStatus.UNAUTHORIZED, "unauthorized");
    namesByCode.put(HttpResponseStatus.FORBIDDEN, "forbidden");
    namesByCode.put(HttpResponseStatus.NOT_FOUND, "notFound");
    namesByCode.put(HttpResponseStatus.TOO_MANY_REQUESTS, "tooManyRequests");
    namesByCode.put(HttpResponseStatus.INTERNAL_SERVER_ERROR, "serverError");

    // Create the proper metrics containers.
    for (Map.Entry<HttpResponseStatus, String> entry : namesByCode.entrySet()) {
      String meterName = MetricRegistry.name("responseCodes", entry.getValue());
      contextBuilder.meterByStatusCode(entry.getKey(), metricRegistry.meter(meterName));
    }
  }

  public void addHealthCheck(String name, HealthCheck check) {
    healthCheckRegistry.register(name, check);
  }

  public void scheduleHealthChecks(EventLoopGroup workerGroup) {
    scheduleHealthChecks(workerGroup, 60, 60, TimeUnit.SECONDS);
  }

  public void scheduleHealthChecks(
      EventLoopGroup workerGroup, int initialDelay, int delay, TimeUnit timeUnit) {

    workerGroup.scheduleWithFixedDelay(
        () -> healthCheckRegistry.runHealthChecks(workerGroup), initialDelay, delay, timeUnit);
  }

  public MetricRegistry getMetricRegistry() {
    return metricRegistry;
  }

  private void addAdminRoutes(Routes routes) {
    MetricsModule metricsModule = new MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, true);
    ObjectMapper metricsMapper = new ObjectMapper().registerModule(metricsModule);
    ObjectMapper healthMapper = new ObjectMapper();

    /*
    '/info' -> should expose version number, git commit number, etc
    '/metrics' -> should return the metrics reporters in JSON format
    '/health' -> should expose a summary of downstream health checks
    '/ping' -> should respond with a 200-OK status code and the text 'PONG'
    '/ready' -> should expose a Kubernetes or ELB specific healthcheck for liveliness
    '/restart' -> restart service (should be restricted to approved devs / tooling)
     '/killkillkill' -> shutdown service (should be restricted to approved devs / tooling)```
     */

    routes.get("/info", AdminHandlers.infoHandler());
    routes.get("/metrics", AdminHandlers.metricsHandler(metricRegistry, metricsMapper));
    routes.get("/health", AdminHandlers.healthCheckHandler(healthCheckRegistry, healthMapper));
    routes.get("/ping", AdminHandlers.pingHandler());
    routes.get("/ready", AdminHandlers.readyHandler());
    routes.get("/restart", AdminHandlers.restartHandler(this));
    routes.get("/killkillkill", AdminHandlers.killHandler(this));

    routes.get("/gc", AdminHandlers.pingHandler());
  }

  /**
   * Builds an initializer that sets up the server pipeline, override this method to customize your
   * pipeline.
   *
   * @param state a value object with server wide state
   * @return a ChannelInitializer that builds this servers ChannelPipeline
   */
  public ChannelInitializer<Channel> initializer(State state) {
    return new ServerChannelInitializer(state);
  }

  /**
   * The listenAndServe method is the primary entry point for the server and should only be called
   * once and only from the main thread. Routes added after this is invoked will not be served.
   *
   * @throws IOException throws in the event the network services, as specified, cannot be accessed
   */
  public void listenAndServe() throws IOException {
    // Finalize the routes this serves.
    contextBuilder.routes(routes.compile(metricRegistry));

    XrpcConnectionContext ctx = contextBuilder.build();

    State state =
        State.builder()
            .config(config)
            .globalConnectionLimiter(
                new ConnectionLimiter(
                    metricRegistry, config.maxConnections())) // All endpoints for a given service
            .rateLimiter(new ServiceRateLimiter(metricRegistry, config, ctx))
            .whiteListFilter(new WhiteListFilter(metricRegistry, config.ipWhiteList()))
            .blackListFilter(new BlackListFilter(metricRegistry, config.ipBlackList()))
            .firewall(new Firewall(metricRegistry))
            .tls(tls)
            .h1h2(new Http2OrHttpHandler(new UrlRouter(), ctx, config.corsConfig()))
            .build();

    ServerBootstrap b =
        XrpcBootstrapFactory.buildBootstrap(
            config.bossThreadCount(), config.workerThreadCount(), config.workerNameFormat());

    b.childHandler(initializer(state));

    if (config.runBackgroundHealthChecks()) {
      scheduleHealthChecks(b.config().childGroup());
    }

    InetSocketAddress address = new InetSocketAddress(config.port());
    log.info("Listening at {}", address);
    ChannelFuture future = b.bind(address);

    try {
      // Build out the loggers that are specified in the config

      if (config.slf4jReporter()) {
        final Slf4jReporter slf4jReporter =
            Slf4jReporter.forRegistry(metricRegistry)
                .outputTo(LoggerFactory.getLogger(Server.class))
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
        slf4jReporter.start(config.slf4jReporterPollingRate(), TimeUnit.SECONDS);
      }

      if (config.jmxReporter()) {
        final JmxReporter jmxReporter = JmxReporter.forRegistry(metricRegistry).build();
        jmxReporter.start();
      }

      if (config.consoleReporter()) {
        final ConsoleReporter consoleReporter =
            ConsoleReporter.forRegistry(metricRegistry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
        consoleReporter.start(config.consoleReporterPollingRate(), TimeUnit.SECONDS);
      }

      future.await();

    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted waiting for bind");
    }

    if (!future.isSuccess()) {
      throw new IOException("Failed to bind", future.cause());
    }

    channel = future.channel();
  }

  public void shutdown() {
    if (channel == null || !channel.isOpen()) {
      return;
    }

    channel
        .close()
        .addListener(
            future -> {
              if (!future.isSuccess()) {
                log.warn("Error shutting down server", future.cause());
              }
              synchronized (Server.this) {
                // TODO(JR): We should probably be more thoughtful here.
                shutdown();
              }
            });
  }
}
