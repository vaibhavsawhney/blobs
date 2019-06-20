package com.expedia.www.haystack.agent.blobs.server.spi;

import com.expedia.www.haystack.agent.blobs.dispatcher.core.BlobDispatcher;
import com.expedia.www.haystack.agent.blobs.server.api.BlobAgentGrpcServer;
import com.expedia.www.haystack.agent.core.Agent;
import com.expedia.www.haystack.agent.core.config.ConfigurationHelpers;
import com.typesafe.config.Config;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public class BlobAgent implements Agent {
    private static Logger LOGGER = LoggerFactory.getLogger(BlobAgent.class);
    private final static String MAX_BLOB_SIZE_KB = "maxBlobSizeInKB";

    private List<BlobDispatcher> dispatchers;
    private Server server;

    @Override
    public String getName() {
        return "blobs";
    }

    @Override
    public void initialize(Config config) throws Exception {
        dispatchers = loadAndInitializeDispatchers(config, Thread.currentThread().getContextClassLoader());

        final Integer maxBlobSizeInKB = config.getInt(MAX_BLOB_SIZE_KB);

        Validate.notNull(maxBlobSizeInKB, "max message size for blobs needs to be specified");


        final Integer port = config.getInt("port");

        final int maxBlobSizeInBytes = maxBlobSizeInKB * 1024;
        final NettyServerBuilder builder = NettyServerBuilder
                .forPort(port)
                .directExecutor()
                .addService(new BlobAgentGrpcServer(dispatchers, maxBlobSizeInBytes));

        // default max message size in grpc is 4MB. if our maxBlobSize is greater than 4MB then we should configure this
        // limit in the netty based grpc server.
        if (maxBlobSizeInBytes > 4 * 1024 * 1024) {
            builder.maxMessageSize(maxBlobSizeInBytes);
        }

        server = builder.build().start();

        LOGGER.info("blob agent grpc server started on port {}....", port);

        try {
            server.awaitTermination();
        } catch (InterruptedException ex) {
            LOGGER.error("blob agent server has been interrupted with exception", ex);
        }
    }

    private List<BlobDispatcher> loadAndInitializeDispatchers(Config config, ClassLoader cl) {
        List<BlobDispatcher> dispatchers = new ArrayList<>();
        final ServiceLoader<BlobDispatcher> loadedDispatchers = ServiceLoader.load(BlobDispatcher.class, cl);

        for (final BlobDispatcher blobDispatcher : loadedDispatchers) {
            final Map<String, Config> blobsConfig = ConfigurationHelpers.readDispatchersConfig(config, getName());
            blobsConfig
                    .entrySet()
                    .stream()
                    .filter((e) -> e.getKey().equalsIgnoreCase(blobDispatcher.getName()))
                    .forEach((conf) -> {
                        blobDispatcher.initialize(conf.getValue());
                        dispatchers.add(blobDispatcher);
                    });
        }

        Validate.notEmpty(dispatchers, "Blob agent dispatchers can't be an empty set");

        return dispatchers;
    }

    @Override
    public void close() {
        try {
            for (final BlobDispatcher dispatcher : dispatchers) {
                dispatcher.close();
            }
            LOGGER.info("shutting down gRPC server and jmx reporter");
            server.shutdown();
        } catch (Exception ignored) {
        }
    }
}