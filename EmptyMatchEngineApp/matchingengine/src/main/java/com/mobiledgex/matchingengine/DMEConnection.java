package com.mobiledgex.matchingengine;


import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import distributed_match_engine.AppClient;
import distributed_match_engine.MatchEngineApiGrpc;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;


/**
 * Async EdgeEvents StreamObserver to and from DME.
 */
class DMEConnection {

    // Persistent connection:
    MatchingEngine me;
    ManagedChannel channel;
    MatchEngineApiGrpc.MatchEngineApiStub stub;
    Logger logger = Logger.getAnonymousLogger();

    StreamObserver<AppClient.ClientEdgeEvent> sender;
    StreamObserver<AppClient.ServerEdgeEvent> receiver;

    DMEConnection(MatchingEngine me) {
        this.me = me;
    }

    public void open(ManagedChannel channel) {
        this.channel = channel;
        this.stub = MatchEngineApiGrpc.newStub(channel);


        receiver = new StreamObserver<AppClient.ServerEdgeEvent>() {
            @Override
            public void onNext(AppClient.ServerEdgeEvent value) {
                me.getEdgeEventBus().post(value);
            }

            @Override
            public void onError(Throwable t) {
                logger.log(Level.WARNING, "Encountered error in DMEConnection", t);
            }

            @Override
            public void onCompleted() {
                logger.log(Level.INFO, "End of send.");
            }
        };

        sender = stub.withDeadlineAfter(10000, TimeUnit.MILLISECONDS)
            .sendEdgeEvent(receiver);
    }

    public void close() {
        sender.onCompleted();
        receiver.onCompleted();
        if (channel != null) {
            channel.shutdown();
            try {
                channel.awaitTermination(5000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            channel = null;
        }
    }

    public void send(AppClient.ClientEdgeEvent clientEdgeEvent) {
        if (channel == null || channel.isShutdown()) {
            return;
        }

        sender.onNext(clientEdgeEvent);
    }


}
